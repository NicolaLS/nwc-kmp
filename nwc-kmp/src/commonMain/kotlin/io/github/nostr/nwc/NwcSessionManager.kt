package io.github.nostr.nwc

import io.github.nostr.nwc.internal.defaultNwcHttpClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nostr.core.session.RelaySessionOutput
import nostr.core.session.RelaySessionSettings
import nostr.runtime.coroutines.SmartRelaySession

class NwcSessionManager private constructor(
    private val scope: CoroutineScope,
    private val ownsScope: Boolean,
    private val httpClient: HttpClient,
    private val ownsHttpClient: Boolean,
    private val sessionSettings: RelaySessionSettings,
    private val retryPolicy: NwcRetryPolicy
) {

    private val mutex = Mutex()
    private val sessions = mutableMapOf<String, ManagedSession>()

    suspend fun acquire(
        uri: String,
        autoOpen: Boolean = false,
        handleOutput: suspend (String, RelaySessionOutput) -> Unit = { _, _ -> },
        configure: suspend (SmartRelaySession, String) -> Unit = { _, _ -> }
    ): NwcSession = acquire(NwcUri.parse(uri), autoOpen, handleOutput, configure)

    suspend fun acquire(
        uri: NwcUri,
        autoOpen: Boolean = false,
        handleOutput: suspend (String, RelaySessionOutput) -> Unit = { _, _ -> },
        configure: suspend (SmartRelaySession, String) -> Unit = { _, _ -> }
    ): NwcSession = acquire(uri.toCredentials(), autoOpen, handleOutput, configure)

    suspend fun acquire(
        credentials: NwcCredentials,
        autoOpen: Boolean = false,
        handleOutput: suspend (String, RelaySessionOutput) -> Unit = { _, _ -> },
        configure: suspend (SmartRelaySession, String) -> Unit = { _, _ -> }
    ): NwcSession {
        val key = canonicalKey(credentials)
        val (session, newlyCreated) = mutex.withLock {
            val existing = sessions[key]
            if (existing != null) {
                existing.refs += 1
                return@withLock existing.session to false
            }
            val created = NwcSession.create(
                credentials = credentials,
                scope = scope,
                httpClient = httpClient,
                sessionSettings = sessionSettings,
                retryPolicy = retryPolicy
            )
            sessions[key] = ManagedSession(created, 1)
            created to true
        }
        if (autoOpen && newlyCreated) {
            try {
                session.open(handleOutput, configure)
            } catch (failure: Throwable) {
                mutex.withLock { sessions.remove(key) }
                throw failure
            }
        }
        return session
    }

    suspend fun release(session: NwcSession) {
        val shouldClose = mutex.withLock {
            val key = canonicalKey(session.credentials)
            val managed = sessions[key] ?: return
            managed.refs -= 1
            if (managed.refs <= 0) {
                sessions.remove(key)
                true
            } else {
                false
            }
        }
        if (shouldClose) {
            session.close()
        }
    }

    suspend fun shutdown() {
        val snapshot = mutex.withLock {
            val values = sessions.values.toList()
            sessions.clear()
            values
        }
        snapshot.forEach { managed ->
            managed.session.close()
        }
        if (ownsHttpClient) {
            runCatching { httpClient.close() }
        }
        if (ownsScope) {
            scope.cancel()
        }
    }

    internal suspend fun activeSessionCount(): Int = mutex.withLock { sessions.size }

    private fun canonicalKey(credentials: NwcCredentials): String =
        credentials.toUriString()

    private data class ManagedSession(
        val session: NwcSession,
        var refs: Int
    )

    companion object {
        fun create(
            scope: CoroutineScope? = null,
            httpClient: HttpClient? = null,
            sessionSettings: RelaySessionSettings = RelaySessionSettings(),
            retryPolicy: NwcRetryPolicy = NwcRetryPolicy.Default
        ): NwcSessionManager {
            val managedScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val ownsScope = scope == null
            val (client, ownsClient) = httpClient?.let { it to false } ?: run {
                defaultNwcHttpClient() to false
            }
            return NwcSessionManager(
                scope = managedScope,
                ownsScope = ownsScope,
                httpClient = client,
                ownsHttpClient = ownsClient,
                sessionSettings = sessionSettings,
                retryPolicy = retryPolicy
            )
        }
    }
}
