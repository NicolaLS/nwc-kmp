package io.github.nostr.nwc

data class NwcRetryPolicy(
    val enabled: Boolean = true,
    val reconnectDelayMillis: Long = 5_000L,
    val maxReconnectDelayMillis: Long = 60_000L,
    val backoffMultiplier: Double = 2.0,
    val jitterRatio: Double = 0.2
) {
    companion object {
        val Default = NwcRetryPolicy()
    }
}
