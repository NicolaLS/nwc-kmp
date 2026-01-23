package io.github.nicolals.nwc

/**
 * Represents errors that can occur during NWC operations.
 *
 * ## Error Categories
 *
 * - [WalletError]: Error returned by the wallet service (e.g., insufficient balance)
 * - [ProtocolError]: Invalid protocol messages or unexpected responses
 * - [ConnectionError]: Network or relay connection issues
 * - [Timeout]: Operation timed out
 * - [Cancelled]: Operation was cancelled
 */
sealed class NwcError {
    /**
     * Human-readable description of the error.
     */
    abstract val message: String

    /**
     * Error returned by the wallet service.
     *
     * These are errors defined in the NIP-47 specification.
     */
    data class WalletError(
        val code: NwcErrorCode,
        override val message: String,
    ) : NwcError()

    /**
     * Invalid protocol message or unexpected response format.
     */
    data class ProtocolError(
        override val message: String,
        val cause: Throwable? = null,
    ) : NwcError()

    /**
     * Network or relay connection error.
     */
    data class ConnectionError(
        override val message: String,
        val cause: Throwable? = null,
    ) : NwcError()

    /**
     * Operation timed out waiting for a response.
     */
    data class Timeout(
        override val message: String = "Operation timed out",
        val durationMs: Long? = null,
    ) : NwcError()

    /**
     * Payment was sent but status is unknown.
     *
     * This occurs when:
     * - A payment request was sent to the relay
     * - The request timed out
     * - Verification showed the payment is still pending
     *
     * The payment may eventually succeed or fail. Use [paymentHash] to track it.
     */
    data class PaymentPending(
        override val message: String = "Payment sent but not yet confirmed",
        val paymentHash: String? = null,
    ) : NwcError()

    /**
     * Operation was cancelled.
     */
    data class Cancelled(
        override val message: String = "Operation cancelled",
    ) : NwcError()

    /**
     * Encryption or decryption failed.
     */
    data class CryptoError(
        override val message: String,
        val cause: Throwable? = null,
    ) : NwcError()
}

/**
 * NIP-47 error codes returned by wallet services.
 */
enum class NwcErrorCode(val code: String) {
    /** The client is sending commands too fast. */
    RATE_LIMITED("RATE_LIMITED"),

    /** The command is not known or is intentionally not implemented. */
    NOT_IMPLEMENTED("NOT_IMPLEMENTED"),

    /** The wallet does not have enough funds. */
    INSUFFICIENT_BALANCE("INSUFFICIENT_BALANCE"),

    /** The wallet has exceeded its spending quota. */
    QUOTA_EXCEEDED("QUOTA_EXCEEDED"),

    /** This public key is not allowed to do this operation. */
    RESTRICTED("RESTRICTED"),

    /** This public key has no wallet connected. */
    UNAUTHORIZED("UNAUTHORIZED"),

    /** An internal error occurred in the wallet. */
    INTERNAL("INTERNAL"),

    /** The payment failed (timeout, no routes, etc.). */
    PAYMENT_FAILED("PAYMENT_FAILED"),

    /** The invoice could not be found. */
    NOT_FOUND("NOT_FOUND"),

    /** The encryption type is not supported by the wallet. */
    UNSUPPORTED_ENCRYPTION("UNSUPPORTED_ENCRYPTION"),

    /** Other error not covered by specific codes. */
    OTHER("OTHER"),

    /** Unknown error code. */
    UNKNOWN("UNKNOWN");

    companion object {
        fun fromCode(code: String): NwcErrorCode {
            return entries.find { it.code == code } ?: UNKNOWN
        }
    }
}

/**
 * Exception wrapper for [NwcError].
 *
 * Useful when you need to throw errors in coroutine contexts.
 */
class NwcException(
    val error: NwcError,
) : Exception(error.message, (error as? NwcError.ProtocolError)?.cause ?: (error as? NwcError.ConnectionError)?.cause)
