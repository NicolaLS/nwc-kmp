package io.github.nicolals.nwc

import kotlin.jvm.JvmInline

/**
 * Represents a Bitcoin amount in millisatoshis (msats).
 *
 * This is the canonical unit for lightning payments. The NWC protocol uses
 * millisatoshis for all amount fields.
 *
 * ## Usage
 *
 * ```kotlin
 * val amount = Amount.fromSats(1000)
 * println(amount.msats) // 1000000
 * println(amount.sats)  // 1000
 *
 * val msatAmount = Amount(50000)
 * println(msatAmount.sats) // 50 (truncated)
 * ```
 */
@JvmInline
value class Amount(val msats: Long) : Comparable<Amount> {

    /**
     * Amount in satoshis (1 sat = 1000 msats).
     * Fractional satoshis are truncated.
     */
    val sats: Long get() = msats / 1000

    /**
     * Amount in whole Bitcoin (1 BTC = 100,000,000 sats).
     */
    val btc: Double get() = msats / 100_000_000_000.0

    override fun compareTo(other: Amount): Int = msats.compareTo(other.msats)

    operator fun plus(other: Amount): Amount = Amount(msats + other.msats)
    operator fun minus(other: Amount): Amount = Amount(msats - other.msats)

    override fun toString(): String = when {
        msats == 0L -> "0 sats"
        msats < 1000 -> "$msats msats"
        msats < 100_000_000_000 -> "${sats.formatWithCommas()} sats"
        else -> "${btc.formatBtc()} BTC"
    }

    companion object {
        val ZERO = Amount(0)

        /**
         * Creates an Amount from satoshis.
         */
        fun fromSats(sats: Long): Amount = Amount(sats * 1000)

        /**
         * Creates an Amount from millisatoshis.
         */
        fun fromMsats(msats: Long): Amount = Amount(msats)
    }
}

private fun Long.formatWithCommas(): String {
    val str = this.toString()
    val result = StringBuilder()
    var count = 0
    for (i in str.lastIndex downTo 0) {
        if (count > 0 && count % 3 == 0) {
            result.insert(0, ',')
        }
        result.insert(0, str[i])
        count++
    }
    return result.toString()
}

private fun Double.formatBtc(): String {
    // Format with 8 decimal places for BTC
    val wholePart = this.toLong()
    val fractionalPart = ((this - wholePart) * 100_000_000).toLong()
    val fractionalStr = fractionalPart.toString().padStart(8, '0')
    return "$wholePart.$fractionalStr"
}
