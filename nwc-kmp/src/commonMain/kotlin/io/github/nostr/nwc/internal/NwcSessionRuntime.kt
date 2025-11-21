package io.github.nostr.nwc.internal

import io.github.nostr.nwc.NwcRetryPolicy
import io.github.nostr.nwc.internal.NwcSessionRuntime.SessionHandle
import io.github.nostr.nwc.logging.NwcLog
import io.github.nostr.nwc.model.RelayConnectionStatus
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import nostr.codec.kotlinx.serialization.KotlinxSerializationWireCodec
import nostr.core.model.Event
import nostr.core.session.RelaySessionOutput
import nostr.core.session.RelaySessionSettings
import kotlin.math.pow
import kotlin.random.Random
import nostr.runtime.coroutines.RelaySessionManager
import nostr.transport.ktor.KtorRelayConnectionFactory

internal class NwcSessionRuntime(
    private val scope: CoroutineScope,
    private val httpClient: HttpClient,
    private val wireCodec: KotlinxSerializationWireCodec,
    private val sessionSettings: RelaySessionSettings,
    private val retryPolicy: NwcRetryPolicy
) {

    private val logTag = "NwcSessionRuntime"

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
    private val reconnectAttempts = mutableMapOf<String, Int>()
    private val sessionsMutex = kotlinx.coroutines.sync.Mutex()
    private val sessionHandlesState = MutableStateFlow<List<SessionHandle>>(emptyList())
    private var shuttingDown = false

    val sessionHandles: List<SessionHandle>
        get() = sessionHandlesState.value

    val connectionStates = relayStates.asStateFlow()

    suspend fun start(
        relays: List<String>,
        handleOutput: suspend (String, RelaySessionOutput) -> Unit,
        configure: suspend (RelaySessionManager.ManagedRelaySession, String) -> Unit
    ) {
        if (sessionsMutex.withLock { sessions.isNotEmpty() }) {
            throw IllegalStateException("NWC session runtime already started")
        }
        val distinct = relays.distinct()
        try {
            coroutineScope {
                distinct.map { relay ->
                    async { startRelay(relay, handleOutput, configure) }
                }.awaitAll()
            }
        } catch (failure: Throwable) {
            shutdown()
            throw failure
        }
    }

    suspend fun publish(event: Event) {
        val snapshot = sessionsMutex.withLock { sessions.values.toList() }
        NwcLog.debug(logTag) { "Publishing event ${event.id} to ${snapshot.size} relay(s)" }
        var successful = false
        var failure: Throwable? = null
        coroutineScope {
            snapshot.map { session ->
                async {
                    val result = runCatching { session.session.publish(event) }
                    if (result.isSuccess) {
                        successful = true
                    } else {
                        NwcLog.warn(logTag, result.exceptionOrNull()) {
                            "Failed to publish event ${event.id} to relay ${session.url}"
                        }
                        failure = result.exceptionOrNull()
                    }
                }
            }.awaitAll()
        }
        if (!successful) {
            NwcLog.error(logTag, failure) { "Failed to publish event ${event.id} to any relay" }
            throw failure ?: IllegalStateException("Failed to publish event ${event.id}")
        }
    }

    suspend fun publishTo(relay: String, event: Event) {
        val session = sessionsMutex.withLock { sessions[relay] }
        if (session == null) {
            NwcLog.warn(logTag) { "Ignoring publish to $relay; no active session" }
            return
        }
        runCatching { session.session.publish(event) }
            .onFailure { NwcLog.warn(logTag, it) { "Failed to publish event ${event.id} to relay $relay" } }
    }

    suspend fun authenticate(relay: String, event: Event) {
        val session = sessionsMutex.withLock { sessions[relay] }
        if (session == null) {
            NwcLog.warn(logTag) { "Ignoring auth dispatch to $relay; no active session" }
            return
        }
        val result = runCatching { session.session.authenticate(event) }
        result.onFailure { NwcLog.warn(logTag, it) { "Failed to send auth event ${event.id} to relay $relay" } }
        result.getOrThrow()
    }

    suspend fun shutdown() {
        NwcLog.info(logTag) { "Shutting down session runtime" }
        shuttingDown = true
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        val current = sessionsMutex.withLock {
            val snapshot = sessions.values.toList()
            sessions.clear()
            sessionHandlesState.value = emptyList()
            snapshot
        }
        reconnectAttempts.clear()
        current.forEach { session ->
            session.outputsJob.cancelAndJoin()
            session.snapshotsJob.cancelAndJoin()
            runCatching { session.session.release() }
        }
        sessionManager.shutdown()
        relayStates.value = emptyMap()
        NwcLog.info(logTag) { "Session runtime shutdown complete" }
    }

    private fun updateRelayState(relay: String, status: RelayConnectionStatus) {
        val current = relayStates.value
        if (current[relay] == status) return
        NwcLog.debug(logTag) { "Relay $relay connection state -> $status" }
        relayStates.value = current + (relay to status)
    }

    private suspend fun scheduleReconnectIfNeeded(relay: String, snapshot: nostr.core.session.ConnectionSnapshot) {
        if (!retryPolicy.enabled || shuttingDown) return
        val shouldReconnect = when (snapshot) {
            is nostr.core.session.ConnectionSnapshot.Failed -> true
            is nostr.core.session.ConnectionSnapshot.Disconnected -> true
            else -> false
        }
        if (!shouldReconnect) return
        if (reconnectJobs[relay]?.isActive == true) return
        val session = sessionsMutex.withLock { sessions[relay] } ?: return
        val delayMillis = nextReconnectDelayMillis(relay)
        reconnectJobs[relay] = scope.launch {
            delay(delayMillis)
            NwcLog.info(logTag) { "Reconnecting relay $relay after ${delayMillis}ms" }
            kotlin.runCatching { session.session.connect() }
                .onSuccess { reconnectAttempts[relay] = 0 }
                .onFailure { NwcLog.warn(logTag, it) { "Reconnect attempt failed for relay $relay" } }
        }
    }

    private suspend fun startRelay(
        relay: String,
        handleOutput: suspend (String, RelaySessionOutput) -> Unit,
        configure: suspend (RelaySessionManager.ManagedRelaySession, String) -> Unit
    ) {
        NwcLog.info(logTag) { "Acquiring relay session for $relay" }
        updateRelayState(relay, RelayConnectionStatus.CONNECTING)
        val managed = sessionManager.acquire(relay)
        val outputsJob = scope.launch {
            managed.outputs.collect { output ->
                NwcLog.trace(logTag) { "Output from $relay: ${output::class.simpleName}" }
                handleOutput(relay, output)
            }
        }
        val snapshotsJob = scope.launch {
            managed.connectionSnapshots.collect { snapshot ->
                NwcLog.debug(logTag) { "Relay $relay snapshot ${snapshot::class.simpleName}" }
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
            NwcLog.error(logTag, started.exceptionOrNull()) { "Failed to start relay session for $relay" }
            snapshotsJob.cancel()
            outputsJob.cancel()
            reconnectJobs.remove(relay)?.cancel()
            managed.release()
            throw started.exceptionOrNull()!!
        }
        NwcLog.info(logTag) { "Relay session established for $relay" }
        reconnectAttempts[relay] = 0
        sessionsMutex.withLock {
            sessions[relay] = RelaySession(relay, managed, outputsJob, snapshotsJob)
            sessionHandlesState.value = sessions.values.map { it.handle }
        }
    }

    private fun nextReconnectDelayMillis(relay: String): Long {
	val attempt = reconnectAttempts[relay] ?: 0
        val base = retryPolicy.reconnectDelayMillis.coerceAtLeast(0L)
        val multiplier = retryPolicy.backoffMultiplier.coerceAtLeast(1.0)
        val maxDelay = retryPolicy.maxReconnectDelayMillis.coerceAtLeast(base)
        val jitterRatio = retryPolicy.jitterRatio.coerceIn(0.0, 1.0)
        val exponential = (base * multiplier.pow(attempt)).toLong().coerceAtMost(maxDelay)
        val jitterSpread = (exponential * jitterRatio).toLong()
        reconnectAttempts[relay] = attempt + 1
        if (jitterSpread <= 0) return exponential
        val min = (exponential - jitterSpread).coerceAtLeast(0L)
        val max = exponential + jitterSpread
        return Random.nextLong(min, max + 1)
    }

    private fun nostr.core.session.ConnectionSnapshot.toRelayStatus(): RelayConnectionStatus = when (this) {
        is nostr.core.session.ConnectionSnapshot.Connecting -> RelayConnectionStatus.CONNECTING
        is nostr.core.session.ConnectionSnapshot.Connected -> RelayConnectionStatus.READY
        is nostr.core.session.ConnectionSnapshot.Disconnecting -> RelayConnectionStatus.CONNECTING
        is nostr.core.session.ConnectionSnapshot.Failed -> RelayConnectionStatus.FAILED
        is nostr.core.session.ConnectionSnapshot.Disconnected -> RelayConnectionStatus.DISCONNECTED
    }
}
