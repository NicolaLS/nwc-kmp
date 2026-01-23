package io.github.nicolals.nwc

/**
 * Parameters for paying a BOLT-11 invoice.
 */
data class PayInvoiceParams(
    /**
     * BOLT-11 encoded invoice string.
     */
    val invoice: String,

    /**
     * Optional amount to pay. If not specified, the invoice amount is used.
     * Required for zero-amount invoices.
     */
    val amount: Amount? = null,
)

/**
 * Result of a successful payment.
 */
data class PaymentResult(
    /**
     * Payment preimage (hex encoded).
     */
    val preimage: String,

    /**
     * Network fees paid.
     */
    val feesPaid: Amount? = null,
)

/**
 * Parameters for a keysend payment.
 */
data class KeysendParams(
    /**
     * Destination node's public key.
     */
    val pubkey: String,

    /**
     * Amount to send.
     */
    val amount: Amount,

    /**
     * Optional preimage. If not provided, the wallet generates one.
     */
    val preimage: String? = null,

    /**
     * Optional TLV records to include in the payment.
     */
    val tlvRecords: List<TlvRecord> = emptyList(),
)

/**
 * TLV (Type-Length-Value) record for keysend payments.
 */
data class TlvRecord(
    /**
     * TLV type.
     */
    val type: Long,

    /**
     * TLV value (hex encoded).
     */
    val value: String,
)

/**
 * Parameters for creating a new invoice.
 */
data class MakeInvoiceParams(
    /**
     * Amount for the invoice.
     */
    val amount: Amount,

    /**
     * Optional description for the invoice.
     */
    val description: String? = null,

    /**
     * Optional description hash (hex encoded).
     * Use this instead of description for longer descriptions.
     */
    val descriptionHash: String? = null,

    /**
     * Expiry time in seconds from creation.
     */
    val expirySeconds: Long? = null,
)

/**
 * Parameters for looking up an invoice.
 */
data class LookupInvoiceParams(
    /**
     * Payment hash to look up (hex encoded).
     * Either paymentHash or invoice must be provided.
     */
    val paymentHash: String? = null,

    /**
     * BOLT-11 invoice to look up.
     * Either paymentHash or invoice must be provided.
     */
    val invoice: String? = null,
) {
    init {
        require(paymentHash != null || invoice != null) {
            "Either paymentHash or invoice must be provided"
        }
    }
}

/**
 * Parameters for listing transactions.
 */
data class ListTransactionsParams(
    /**
     * Start timestamp (Unix seconds, inclusive).
     */
    val from: Long? = null,

    /**
     * End timestamp (Unix seconds, inclusive).
     */
    val until: Long? = null,

    /**
     * Maximum number of transactions to return.
     */
    val limit: Int? = null,

    /**
     * Offset for pagination.
     */
    val offset: Int? = null,

    /**
     * Whether to include unpaid invoices.
     */
    val unpaid: Boolean? = null,

    /**
     * Filter by transaction type.
     */
    val type: TransactionType? = null,
)

/**
 * Item for multi-pay-invoice requests.
 */
data class MultiPayInvoiceItem(
    /**
     * Unique ID for this item (used to correlate responses).
     */
    val id: String,

    /**
     * BOLT-11 encoded invoice string.
     */
    val invoice: String,

    /**
     * Optional amount to pay.
     */
    val amount: Amount? = null,
)

/**
 * Item for multi-pay-keysend requests.
 */
data class MultiKeysendItem(
    /**
     * Unique ID for this item (used to correlate responses).
     */
    val id: String,

    /**
     * Destination node's public key.
     */
    val pubkey: String,

    /**
     * Amount to send.
     */
    val amount: Amount,

    /**
     * Optional preimage.
     */
    val preimage: String? = null,

    /**
     * Optional TLV records.
     */
    val tlvRecords: List<TlvRecord> = emptyList(),
)

/**
 * Result for a single item in a multi-pay operation.
 */
sealed class MultiPayItemResult {
    /**
     * Payment succeeded.
     */
    data class Success(val result: PaymentResult) : MultiPayItemResult()

    /**
     * Payment failed.
     */
    data class Failed(val error: NwcError) : MultiPayItemResult()
}
