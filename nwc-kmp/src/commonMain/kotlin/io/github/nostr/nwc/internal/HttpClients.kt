package io.github.nostr.nwc.internal

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.websocket.WebSockets

private const val DEFAULT_PING_INTERVAL_MS = 15_000L
private const val DEFAULT_MAX_FRAME_SIZE = Long.MAX_VALUE
private const val DEFAULT_CONNECT_TIMEOUT_MS = 8_000L
private const val DEFAULT_SOCKET_TIMEOUT_MS = 20_000L

/**
 * Shared, lazily-initialized HTTP client tuned for NWC websocket traffic.
 * Consumers can still inject their own, but using the shared instance avoids
 * duplicate engines and lost connection reuse when multiple clients run in-process.
 */
internal object NwcHttpClients {
    val shared: HttpClient by lazy { build() }

    fun build(): HttpClient = HttpClient {
        install(WebSockets) {
	    pingIntervalMillis = DEFAULT_PING_INTERVAL_MS
            maxFrameSize = DEFAULT_MAX_FRAME_SIZE
        }
        install(HttpTimeout) {
            connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MS
            socketTimeoutMillis = DEFAULT_SOCKET_TIMEOUT_MS
            // The NWC protocol uses long-lived websockets; disable the generic request timeout.
	    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        }
    }
}

internal fun defaultNwcHttpClient(): HttpClient = NwcHttpClients.shared
