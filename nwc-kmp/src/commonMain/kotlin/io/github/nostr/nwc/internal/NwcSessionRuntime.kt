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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import nostr.codec.kotlinx.serialization.KotlinxSerializationWireCodec
import nostr.core.model.Event
import nostr.core.model.Filter
import nostr.core.session.ConnectionSnapshot
import nostr.core.session.RelaySessionOutput
import nostr.core.session.RelaySessionSettings
import nostr.runtime.coroutines.CoroutineNostrRuntime
import nostr.runtime.coroutines.SharedSubscription
import nostr.runtime.coroutines.SmartRelaySession
import nostr.transport.ktor.KtorRelayConnectionFactory

private const val SUBSCRIPTION_SETUP_TIMEOUT_MS = 5_000L

internal class NwcSessionRuntime(
    private val scope: CoroutineScope,
    private val httpClient: HttpClient,
    private val wireCodec: KotlinxSerializationWireCodec,
    private val sessionSettings: RelaySessionSettings,
    private val retryPolicy: NwcRetryPolicy
) {

    private val logTag = "NwcSessionRuntime"

    @ConsistentCopyVisibility
    internal data class SessionHandle internal constructor(
        val url: String,
        internal val session: SmartRelaySession,
        internal var responseSubscription: SharedSubscription? = null
    )

    private data class RelaySession(
        val url: String,
        val runtime: CoroutineNostrRuntime,
        val session: SmartRelaySession,
        val outputsJob: Job,
        val snapshotsJob: Job,
        val handle: SessionHandle
    )

    private val sessions = mutableMapOf<String, RelaySession>()
    private val relayStates = MutableStateFlow<Map<String, RelayConnectionStatus>>(emptyMap())
    private val sessionsMutex = kotlinx.coroutines.sync.Mutex()
    private val sessionHandlesState = MutableStateFlow<List<SessionHandle>>(emptyList())
    private var shuttingDown = false

    val sessionHandles: List<SessionHandle>
        get() = sessionHandlesState.value

    val connectionStates = relayStates.asStateFlow()

    suspend fun start(
        relays: List<String>,
        handleOutput: suspend (String, RelaySessionOutput) -> Unit,
        configure: suspend (SmartRelaySession, String) -> Unit
    ) {
        if (sessionsMutex.withLock { sessions.isNotEmpty() }) {
            throw IllegalStateException("NWC session runtime already started")
        }
        val distinct = relays.distinct()
        val results = coroutineScope {
            distinct.map { relay ->
                async { relay to ensureRelay(relay, handleOutput, configure) }
            }.awaitAll()
        }
        val successes = results.filter { it.second }.map { it.first }

        if (successes.isEmpty()) {
            shutdown()
            throw IllegalStateException("Failed to start any relay session")
        }
    }

    /**
     * Best-effort attempt to start a relay if not already active.
     * Returns true when the relay is active (either previously or after start).
     */
    suspend fun ensureRelay(
        relay: String,
        handleOutput: suspend (String, RelaySessionOutput) -> Unit,
        configure: suspend (SmartRelaySession, String) -> Unit
    ): Boolean {
        if (sessionsMutex.withLock { sessions.containsKey(relay) }) {
            return true
        }
        return startRelay(relay, handleOutput, configure)
    }

    /**
     * Create a SharedSubscription for efficient request-response patterns.
     * The subscription is stored in the SessionHandle for reuse across requests.
     *
     * Uses a timeout to prevent blocking forever if the relay is slow or unreachable.
     * If subscription creation times out, returns null and the relay won't be used
     * for request-response operations.
     *
     * Checks network availability before attempting subscription setup to enable
     * fast-fail behavior when no network is available.
     *
     * @param relay The relay URL
     * @param filters Filters for the subscription (typically matching NWC responses)
     * @param timeoutMillis Timeout for subscription setup (default: 5 seconds)
     * @param checkNetwork If true, checks network availability before subscription setup
     * @return The created SharedSubscription, or null if relay not found or timeout
     * @throws NetworkUnavailableException if checkNetwork is true and no network is available
     */
    suspend fun createResponseSubscription(
        relay: String,
        filters: List<Filter>,
        timeoutMillis: Long = SUBSCRIPTION_SETUP_TIMEOUT_MS,
        checkNetwork: Boolean = false
    ): SharedSubscription? {
        val relaySession = sessionsMutex.withLock { sessions[relay] } ?: return null
        val subscription = withTimeoutOrNull(timeoutMillis) {
            relaySession.session.createSharedSubscription(
                filters = filters,
                subscriptionId = SharedSubscription.generateId("nwc-resp"),
                checkNetwork = checkNetwork
            )
        }
        if (subscription == null) {
            NwcLog.warn(logTag) {
                "Subscription setup timed out after ${timeoutMillis}ms for relay $relay - " +
                    "relay may be slow or unreachable"
            }
            return null
        }
        relaySession.handle.responseSubscription = subscription
        NwcLog.debug(logTag) { "Created shared response subscription for relay $relay" }
        return subscription
    }

    suspend fun publish(event: Event) {
        val snapshot = sessionsMutex.withLock { sessions.values.toList() }
        NwcLog.debug(logTag) { "Publishing event ${event.id} to ${snapshot.size} relay(s)" }
        var successful = false
        var failure: Throwable? = null
        coroutineScope {
            snapshot.map { relaySession ->
                async {
                    val result = runCatching { relaySession.session.publish(event) }
                    if (result.isSuccess) {
                        successful = true
                    } else {
                        NwcLog.warn(logTag, result.exceptionOrNull()) {
                            "Failed to publish event ${event.id} to relay ${relaySession.url}"
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
        val relaySession = sessionsMutex.withLock { sessions[relay] }
        if (relaySession == null) {
            NwcLog.warn(logTag) { "Ignoring publish to $relay; no active session" }
            return
        }
        runCatching { relaySession.session.publish(event) }
            .onFailure { NwcLog.warn(logTag, it) { "Failed to publish event ${event.id} to relay $relay" } }
    }

    suspend fun shutdown() {
        NwcLog.info(logTag) { "Shutting down session runtime" }
        shuttingDown = true
        val current = sessionsMutex.withLock {
            val snapshot = sessions.values.toList()
            sessions.clear()
            sessionHandlesState.value = emptyList()
            snapshot
        }
        current.forEach { relaySession ->
            // Close shared subscriptions
            relaySession.handle.responseSubscription?.let { subscription ->
                runCatching { subscription.close() }
            }
            relaySession.outputsJob.cancelAndJoin()
            relaySession.snapshotsJob.cancelAndJoin()
            runCatching { relaySession.runtime.shutdown() }
        }
        relayStates.value = emptyMap()
        NwcLog.info(logTag) { "Session runtime shutdown complete" }
    }

    private fun updateRelayState(relay: String, status: RelayConnectionStatus) {
        val current = relayStates.value
        if (current[relay] == status) return
        NwcLog.debug(logTag) { "Relay $relay connection state -> $status" }
        relayStates.value = current + (relay to status)
    }

    private suspend fun startRelay(
        relay: String,
        handleOutput: suspend (String, RelaySessionOutput) -> Unit,
        configure: suspend (SmartRelaySession, String) -> Unit
    ): Boolean {
        NwcLog.info(logTag) { "Creating relay session for $relay" }
        updateRelayState(relay, RelayConnectionStatus.CONNECTING)

        val runtime = CoroutineNostrRuntime(
            scope = scope,
            connectionFactory = KtorRelayConnectionFactory(scope, httpClient),
            wireEncoder = wireCodec,
            wireDecoder = wireCodec,
            reconnectionPolicy = retryPolicy.toReconnectionPolicy(),
            settings = sessionSettings
        )

        // Wrap the runtime in a SmartRelaySession for convenient high-level operations
        val smartSession = SmartRelaySession(runtime, relay, scope)

        val outputsJob = scope.launch {
            smartSession.outputs.collect { output ->
                NwcLog.trace(logTag) { "Output from $relay: ${output::class.simpleName}" }
                handleOutput(relay, output)
            }
        }

        val snapshotsJob = scope.launch {
            smartSession.connectionSnapshots.collect { snapshot ->
                NwcLog.debug(logTag) { "Relay $relay snapshot ${snapshot::class.simpleName}" }
                updateRelayState(relay, snapshot.toRelayStatus())
            }
        }

        val handle = SessionHandle(relay, smartSession)

        val started = kotlin.runCatching {
            // SmartRelaySession auto-connects on first operation, but we explicitly connect here
            // to fail fast if the relay is unreachable during startup
            smartSession.connect()
            configure(smartSession, relay)
        }

        if (started.isFailure) {
            NwcLog.error(logTag, started.exceptionOrNull()) { "Failed to start relay session for $relay" }
            snapshotsJob.cancel()
            outputsJob.cancel()
            runtime.shutdown()
            updateRelayState(relay, RelayConnectionStatus.FAILED)
            return false
        }

        NwcLog.info(logTag) { "Relay session established for $relay" }
        sessionsMutex.withLock {
            sessions[relay] = RelaySession(relay, runtime, smartSession, outputsJob, snapshotsJob, handle)
            sessionHandlesState.value = sessions.values.map { it.handle }
        }
        return true
    }

    private fun ConnectionSnapshot.toRelayStatus(): RelayConnectionStatus = when (this) {
        is ConnectionSnapshot.Connecting -> RelayConnectionStatus.CONNECTING
        is ConnectionSnapshot.Connected -> RelayConnectionStatus.READY
        is ConnectionSnapshot.Disconnecting -> RelayConnectionStatus.CONNECTING
        is ConnectionSnapshot.Failed -> RelayConnectionStatus.FAILED
        is ConnectionSnapshot.Disconnected -> RelayConnectionStatus.DISCONNECTED
    }
}
