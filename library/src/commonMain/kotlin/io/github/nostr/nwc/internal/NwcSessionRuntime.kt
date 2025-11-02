package io.github.nostr.nwc.internal

import io.github.nostr.nwc.internal.NwcSessionRuntime.SessionHandle
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
    private val sessionSettings: RelaySessionSettings
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

    val sessionHandles: List<SessionHandle>
        get() = sessions.map { it.handle }

    suspend fun start(
        relays: List<String>,
        handleOutput: suspend (String, RelaySessionOutput) -> Unit,
        configure: suspend (CoroutineNostrRuntime, String) -> Unit
    ) {
        if (sessions.isNotEmpty()) {
            throw IllegalStateException("NWC session runtime already started")
        }
        relays.distinct().forEach { relay ->
            val runtime = CoroutineNostrRuntime(
                scope = scope,
                connectionFactory = KtorRelayConnectionFactory(scope, httpClient),
                wireEncoder = wireCodec,
                wireDecoder = wireCodec,
                settings = sessionSettings
            )
            val job = scope.launch {
                runtime.outputs.collect { output ->
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
        sessions.forEach { session ->
            session.outputsJob.cancelAndJoin()
            runCatching { session.runtime.shutdown() }
        }
        sessions.clear()
    }
}
