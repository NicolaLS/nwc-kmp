package io.github.nostr.nwc

import io.github.nostr.nwc.internal.NwcSessionRuntime
import io.github.nostr.nwc.internal.defaultNwcHttpClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nostr.codec.kotlinx.serialization.KotlinxSerializationWireCodec
import nostr.core.session.RelaySessionOutput
import nostr.core.session.RelaySessionSettings
import nostr.runtime.coroutines.CoroutineNostrRuntime

class NwcSession private constructor(
    val credentials: NwcCredentials,
    private val scope: CoroutineScope,
    private val ownsScope: Boolean,
    private val httpClient: HttpClient,
    private val ownsHttpClient: Boolean,
    private val sessionSettings: RelaySessionSettings,
    private val wireCodec: KotlinxSerializationWireCodec
) {

    private val runtime = NwcSessionRuntime(
        scope = scope,
        httpClient = httpClient,
        wireCodec = wireCodec,
        sessionSettings = sessionSettings
    )

    private val lifecycleMutex = Mutex()
    private var lifecycle: Lifecycle = Lifecycle.Idle

    val relays: List<String> get() = credentials.relays

    suspend fun isOpen(): Boolean = lifecycleMutex.withLock { lifecycle == Lifecycle.Open }

    internal val runtimeHandles: List<NwcSessionRuntime.SessionHandle>
        get() = runtime.sessionHandles

    suspend fun open(
        handleOutput: suspend (String, RelaySessionOutput) -> Unit = { _, _ -> },
        configure: suspend (CoroutineNostrRuntime, String) -> Unit = { _, _ -> }
    ) {
        lifecycleMutex.withLock {
            check(lifecycle != Lifecycle.Closed) { "NwcSession is already closed." }
            if (lifecycle == Lifecycle.Open) return
            runtime.start(
                relays = credentials.relays,
                handleOutput = handleOutput,
                configure = configure
            )
            lifecycle = Lifecycle.Open
        }
    }

    suspend fun close() {
        val shouldShutdown = lifecycleMutex.withLock {
            if (lifecycle == Lifecycle.Closed) return
            lifecycle = Lifecycle.Closed
            true
        }
        if (shouldShutdown) {
            runtime.shutdown()
            if (ownsHttpClient) {
                runCatching { httpClient.close() }
            }
            if (ownsScope) {
                scope.cancel()
            }
        }
    }

    suspend fun <T> use(
        handleOutput: suspend (String, RelaySessionOutput) -> Unit = { _, _ -> },
        configure: suspend (CoroutineNostrRuntime, String) -> Unit = { _, _ -> },
        block: suspend NwcSession.() -> T
    ): T {
        open(handleOutput, configure)
        return try {
            block()
        } finally {
            close()
        }
    }

    internal fun sessionRuntime(): NwcSessionRuntime = runtime

    private enum class Lifecycle {
        Idle,
        Open,
        Closed
    }

    companion object {
        fun create(
            uri: String,
            scope: CoroutineScope? = null,
            httpClient: HttpClient? = null,
            sessionSettings: RelaySessionSettings = RelaySessionSettings()
        ): NwcSession = create(NwcUri.parse(uri), scope, httpClient, sessionSettings)

        fun create(
            uri: NwcUri,
            scope: CoroutineScope? = null,
            httpClient: HttpClient? = null,
            sessionSettings: RelaySessionSettings = RelaySessionSettings()
        ): NwcSession = create(uri.toCredentials(), scope, httpClient, sessionSettings)

        fun create(
            credentials: NwcCredentials,
            scope: CoroutineScope? = null,
            httpClient: HttpClient? = null,
            sessionSettings: RelaySessionSettings = RelaySessionSettings()
        ): NwcSession {
            val managedScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val ownsScope = scope == null
            val (client, ownsClient) = httpClient?.let { it to false } ?: run {
                defaultNwcHttpClient() to true
            }
            val codec = KotlinxSerializationWireCodec.default()
            return NwcSession(
                credentials = credentials,
                scope = managedScope,
                ownsScope = ownsScope,
                httpClient = client,
                ownsHttpClient = ownsClient,
                sessionSettings = sessionSettings,
                wireCodec = codec
            )
        }
    }
}
