package io.github.nostr.nwc

import nostr.runtime.coroutines.ExponentialBackoffPolicy
import nostr.runtime.coroutines.NoReconnectionPolicy
import nostr.runtime.coroutines.ReconnectionPolicy

/**
 * Configuration for automatic reconnection behavior in NWC sessions.
 *
 * Default values are tuned for wallet operations (payments, get_info, etc.)
 * which are typically foreground user actions requiring fast recovery.
 *
 * @property enabled Whether automatic reconnection is enabled.
 * @property reconnectDelayMillis Initial delay before first retry (default: 200ms for fast recovery).
 * @property maxReconnectDelayMillis Maximum delay cap (default: 5s to maintain responsiveness).
 * @property backoffMultiplier Delay multiplier after each failed attempt (default: 2.0).
 * @property jitterRatio Random variation to prevent thundering herd (default: 0.2 = +/- 20%).
 * @property maxAttempts Maximum retry attempts before giving up (default: null = unlimited).
 */
data class NwcRetryPolicy(
    val enabled: Boolean = true,
    val reconnectDelayMillis: Long = 200L,
    val maxReconnectDelayMillis: Long = 5_000L,
    val backoffMultiplier: Double = 2.0,
    val jitterRatio: Double = 0.2,
    val maxAttempts: Int? = null
) {
    companion object {
        /** Default policy optimized for foreground wallet operations. */
        val Default = NwcRetryPolicy()

        /** Disabled reconnection - connection failures must be handled manually. */
        val Disabled = NwcRetryPolicy(enabled = false)
    }

    /**
     * Converts this policy to a [ReconnectionPolicy] for use with the runtime.
     */
    internal fun toReconnectionPolicy(): ReconnectionPolicy {
        if (!enabled) return NoReconnectionPolicy
        return ExponentialBackoffPolicy(
            baseDelayMillis = reconnectDelayMillis.coerceAtLeast(1L),
            maxDelayMillis = maxReconnectDelayMillis.coerceAtLeast(reconnectDelayMillis),
            maxAttempts = maxAttempts,
            jitterFactor = jitterRatio.coerceIn(0.0, 1.0)
        )
    }
}
