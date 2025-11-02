package io.github.nostr.nwc.internal

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets

internal fun defaultNwcHttpClient(): HttpClient = HttpClient {
    install(WebSockets)
}
