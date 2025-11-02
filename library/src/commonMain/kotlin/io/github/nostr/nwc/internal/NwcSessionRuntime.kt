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
import nostr.runtime.coroutines.CoroutineNostrRuntime
import nostr.transport.ktor.KtorRelayConnectionFactory
import nostr.core.session.RelaySessionSettings

internal class NwcSessionRuntime(
    private val scope: CoroutineScope,
    private val httpClient: HttpClient,
    private val wireCodec: KotlinxSerializationWireCodec,
    private val sessionSettings: RelaySessionSettings,
    private val retryPolicy: NwcRetryPolicy
) {

    internal data class SessionHandle internal constructor(
        val url: String,
        val runtime: CoroutineNostrRuntime
    )

    private data class RelaySession(
        val url: String,
        val runtime: CoroutineNostrRuntime,
        val outputsJob: Job
    ) {
        val handle = SessionHandle(url, runtime)
    }

    private val sessions = mutableListOf<RelaySession>()
    private val relayStates = MutableStateFlow<Map<String, RelayConnectionStatus>>(emptyMap())
    private val reconnectJobs = mutableMapOf<String, Job>()
    private var shuttingDown = false

    val sessionHandles: List<SessionHandle>
        get() = sessions.map { it.handle }

    val connectionStates = relayStates.asStateFlow()

    suspend fun start(
        relays: List<String>,
        handleOutput: suspend (String, RelaySessionOutput) -> Unit,
        configure: suspend (CoroutineNostrRuntime, String) -> Unit
    ) {
        if (sessions.isNotEmpty()) {
            throw IllegalStateException("NWC session runtime already started")
        }
        relays.distinct().forEach { relay ->
            updateRelayState(relay, RelayConnectionStatus.CONNECTING)
            val runtime = CoroutineNostrRuntime(
                scope = scope,
                connectionFactory = KtorRelayConnectionFactory(scope, httpClient),
                wireEncoder = wireCodec,
                wireDecoder = wireCodec,
                settings = sessionSettings
            )
            val job = scope.launch {
                runtime.outputs.collect { output ->
                    if (output is RelaySessionOutput.ConnectionStateChanged) {
                        updateRelayState(relay, output.snapshot.toRelayStatus())
                        if (retryPolicy.enabled) {
                            scheduleReconnectIfNeeded(relay, output.snapshot)
                        }
                    }
                    handleOutput(relay, output)
                }
            }
            val started = kotlin.runCatching {
                runtime.connect(relay)
                configure(runtime, relay)
            }
            if (started.isFailure) {
                job.cancel()
                kotlin.runCatching { runtime.shutdown() }
                throw started.exceptionOrNull()!!
            }
            sessions += RelaySession(relay, runtime, job)
        }
    }

    suspend fun publish(event: Event) {
        var successful = false
        var failure: Throwable? = null
        sessions.forEach { session ->
            val result = runCatching { session.runtime.publish(event) }
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

    suspend fun shutdown() {
        shuttingDown = true
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        sessions.forEach { session ->
            session.outputsJob.cancelAndJoin()
            runCatching { session.runtime.shutdown() }
        }
        sessions.clear()
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
        val session = sessions.firstOrNull { it.url == relay } ?: return
        reconnectJobs[relay] = scope.launch {
            delay(retryPolicy.reconnectDelayMillis)
            kotlin.runCatching { session.runtime.connect(relay) }
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
