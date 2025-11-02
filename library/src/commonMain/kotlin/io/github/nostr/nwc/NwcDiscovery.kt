package io.github.nostr.nwc

import io.github.nostr.nwc.model.NwcResult
import io.github.nostr.nwc.model.NwcWalletDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import nostr.core.session.RelaySessionSettings

suspend fun discoverWallet(
    uri: String,
    sessionSettings: RelaySessionSettings = RelaySessionSettings(),
    requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS
): NwcResult<NwcWalletDescriptor> {
    val parsed = NwcUri.parse(uri)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val client = NwcClient.create(
        uri = parsed,
        scope = scope,
        sessionSettings = sessionSettings,
        requestTimeoutMillis = requestTimeoutMillis
    )
    val result = try {
        client.describeWallet(requestTimeoutMillis)
    } finally {
        client.close()
        scope.cancel()
    }
    return result
}

suspend fun discoverWallet(
    session: NwcSession,
    requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS
): NwcResult<NwcWalletDescriptor> {
    val client = NwcClient.create(
        credentials = session.credentials,
        scope = session.coroutineScope,
        session = session,
        ownsSession = false,
        httpClient = session.httpClientInternal,
        ownsHttpClient = false,
        requestTimeoutMillis = requestTimeoutMillis
    )
    val result = try {
        client.describeWallet(requestTimeoutMillis)
    } finally {
        client.close()
    }
    return result
}
