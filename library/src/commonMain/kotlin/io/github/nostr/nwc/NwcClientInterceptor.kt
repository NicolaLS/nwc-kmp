package io.github.nostr.nwc

import kotlinx.serialization.json.JsonObject
import io.github.nostr.nwc.model.RawResponse
import io.github.nostr.nwc.model.WalletNotification

interface NwcClientInterceptor {
    fun onRequest(method: String, params: JsonObject) = Unit
    fun onResponse(method: String, response: RawResponse) = Unit
    fun onNotification(notification: WalletNotification) = Unit
}
