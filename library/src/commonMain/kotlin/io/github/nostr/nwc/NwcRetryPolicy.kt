package io.github.nostr.nwc

data class NwcRetryPolicy(
    val enabled: Boolean = true,
    val reconnectDelayMillis: Long = 5_000L
) {
    companion object {
        val Default = NwcRetryPolicy()
    }
}
