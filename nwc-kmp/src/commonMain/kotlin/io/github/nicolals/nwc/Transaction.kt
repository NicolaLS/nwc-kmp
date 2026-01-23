package io.github.nicolals.nwc

/**
 * Represents a Lightning transaction (invoice or payment).
 *
 * This model is used for:
 * - Results from `make_invoice` and `lookup_invoice`
 * - Items in `list_transactions` results
 * - Payment notifications
 */
data class Transaction(
    /**
     * Type of transaction.
     */
    val type: TransactionType,

    /**
     * Current state of the transaction.
     */
    val state: TransactionState?,

    /**
     * Payment hash (hex encoded).
     */
    val paymentHash: String,

    /**
     * Transaction amount.
     */
    val amount: Amount,

    /**
     * BOLT-11 encoded invoice string.
     */
    val invoice: String? = null,

    /**
     * Invoice description.
     */
    val description: String? = null,

    /**
     * Invoice description hash (hex encoded).
     */
    val descriptionHash: String? = null,

    /**
     * Payment preimage (hex encoded). Present when settled.
     */
    val preimage: String? = null,

    /**
     * Network fees paid (for outgoing payments).
     */
    val feesPaid: Amount? = null,

    /**
     * When the transaction was created (Unix timestamp).
     */
    val createdAt: Long,

    /**
     * When the invoice expires (Unix timestamp).
     */
    val expiresAt: Long? = null,

    /**
     * When the transaction was settled (Unix timestamp).
     */
    val settledAt: Long? = null,
)

/**
 * Type of Lightning transaction.
 */
enum class TransactionType(val value: String) {
    /** Incoming payment (invoice). */
    INCOMING("incoming"),

    /** Outgoing payment. */
    OUTGOING("outgoing");

    companion object {
        fun fromValue(value: String): TransactionType? =
            entries.find { it.value == value }
    }
}

/**
 * State of a Lightning transaction.
 */
enum class TransactionState(val value: String) {
    /** Transaction is pending (not yet settled). */
    PENDING("pending"),

    /** Transaction has been settled successfully. */
    SETTLED("settled"),

    /** Invoice has expired without being paid. */
    EXPIRED("expired"),

    /** Payment has failed. */
    FAILED("failed");

    companion object {
        fun fromValue(value: String): TransactionState? =
            entries.find { it.value == value }
    }
}
