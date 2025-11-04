package io.github.nostr.nwc.internal

import io.github.nostr.nwc.NwcRetryPolicy
import io.github.nostr.nwc.internal.NwcSessionRuntime.SessionHandle
import io.github.nostr.nwc.model.RelayConnectionStatus
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import nostr.codec.kotlinx.serialization.KotlinxSerializationWireCodec
import nostr.core.model.Event
import nostr.core.session.RelaySessionOutput
import nostr.core.session.RelaySessionSettings
import nostr.runtime.coroutines.CoroutineNostrRuntime
import nostr.runtime.coroutines.RelaySessionManager
import nostr.transport.ktor.KtorRelayConnectionFactory

internal class NwcSessionRuntime(
    private val scope: CoroutineScope,
    private val httpClient: HttpClient,
    private val wireCodec: KotlinxSerializationWireCodec,
    private val sessionSettings: RelaySessionSettings,
    private val retryPolicy: NwcRetryPolicy
) {

    internal data class SessionHandle internal constructor(
        val url: String,
        internal val session: RelaySessionManager.ManagedRelaySession
    )

    private data class RelaySession(
        val url: String,
        val session: RelaySessionManager.ManagedRelaySession,
        val outputsJob: Job,
        val snapshotsJob: Job
    ) {
        val handle = SessionHandle(url, session)
    }

    private val sessionManager = RelaySessionManager(
        scope = scope,
        connectionFactory = KtorRelayConnectionFactory(scope, httpClient),
        wireEncoder = wireCodec,
        wireDecoder = wireCodec,
        sessionSettings = sessionSettings
    )
    private val sessions = mutableMapOf<String, RelaySession>()
    private val relayStates = MutableStateFlow<Map<String, RelayConnectionStatus>>(emptyMap())
    private val reconnectJobs = mutableMapOf<String, Job>()
    private var shuttingDown = false

    val sessionHandles: List<SessionHandle>
        get() = sessions.values.map { it.handle }

    val connectionStates = relayStates.asStateFlow()

    suspend fun start(
        relays: List<String>,
        handleOutput: suspend (String, RelaySessionOutput) -> Unit,
        configure: suspend (RelaySessionManager.ManagedRelaySession, String) -> Unit
    ) {
        if (sessions.isNotEmpty()) {
            throw IllegalStateException("NWC session runtime already started")
        }
        relays.distinct().forEach { relay ->
            updateRelayState(relay, RelayConnectionStatus.CONNECTING)
            val managed = sessionManager.acquire(relay)
            val outputsJob = scope.launch {
                managed.outputs.collect { output ->
                    handleOutput(relay, output)
                }
            }
            val snapshotsJob = scope.launch {
                managed.connectionSnapshots.collect { snapshot ->
                    updateRelayState(relay, snapshot.toRelayStatus())
                    if (retryPolicy.enabled) {
                        scheduleReconnectIfNeeded(relay, snapshot)
                    }
                }
            }
            val started = kotlin.runCatching {
                managed.connect()
                configure(managed, relay)
            }
            if (started.isFailure) {
                snapshotsJob.cancel()
                outputsJob.cancel()
                reconnectJobs.remove(relay)?.cancel()
                managed.release()
                throw started.exceptionOrNull()!!
            }
            sessions[relay] = RelaySession(relay, managed, outputsJob, snapshotsJob)
        }
    }

    suspend fun publish(event: Event) {
        var successful = false
        var failure: Throwable? = null
        sessions.values.forEach { session ->
            val result = runCatching { session.session.publish(event) }
            if (result.isSuccess) {
                successful = true
            } else {
                failure = result.exceptionOrNull()
            }
        }
        if (!successful) {
            throw failure ?: IllegalStateException("Failed to publish event ${event.id}")
        }
    }

    suspend fun publishTo(relay: String, event: Event) {
        sessions[relay]?.session?.publish(event)
    }

    suspend fun authenticate(relay: String, event: Event) {
        sessions[relay]?.session?.authenticate(event)
    }

    suspend fun shutdown() {
        shuttingDown = true
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        val current = sessions.values.toList()
        sessions.clear()
        current.forEach { session ->
            session.outputsJob.cancelAndJoin()
            session.snapshotsJob.cancelAndJoin()
            runCatching { session.session.release() }
        }
        sessionManager.shutdown()
        relayStates.value = emptyMap()
    }

    private fun updateRelayState(relay: String, status: RelayConnectionStatus) {
        val current = relayStates.value
        if (current[relay] == status) return
        relayStates.value = current + (relay to status)
    }

    private fun scheduleReconnectIfNeeded(relay: String, snapshot: nostr.core.session.ConnectionSnapshot) {
        if (!retryPolicy.enabled || shuttingDown) return
        val shouldReconnect = when (snapshot) {
            is nostr.core.session.ConnectionSnapshot.Failed -> true
            is nostr.core.session.ConnectionSnapshot.Disconnected -> true
            else -> false
        }
        if (!shouldReconnect) return
        if (reconnectJobs[relay]?.isActive == true) return
        val session = sessions[relay] ?: return
        reconnectJobs[relay] = scope.launch {
            delay(retryPolicy.reconnectDelayMillis)
            kotlin.runCatching { session.session.connect() }
        }
    }

    private fun nostr.core.session.ConnectionSnapshot.toRelayStatus(): RelayConnectionStatus = when (this) {
        is nostr.core.session.ConnectionSnapshot.Connecting -> RelayConnectionStatus.CONNECTING
        is nostr.core.session.ConnectionSnapshot.Connected -> RelayConnectionStatus.READY
        is nostr.core.session.ConnectionSnapshot.Disconnecting -> RelayConnectionStatus.CONNECTING
        is nostr.core.session.ConnectionSnapshot.Failed -> RelayConnectionStatus.FAILED
        is nostr.core.session.ConnectionSnapshot.Disconnected -> RelayConnectionStatus.DISCONNECTED
    }
}
