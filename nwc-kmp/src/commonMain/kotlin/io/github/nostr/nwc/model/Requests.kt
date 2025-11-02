package io.github.nostr.nwc.model

import kotlinx.serialization.json.JsonObject

data class PayInvoiceParams(
    val invoice: String,
    val amount: BitcoinAmount? = null,
    val metadata: JsonObject? = null
)

data class MultiPayInvoiceItem(
    val id: String? = null,
    val invoice: String,
    val amount: BitcoinAmount? = null,
    val metadata: JsonObject? = null
)

data class KeysendParams(
    val destinationPubkey: String,
    val amount: BitcoinAmount,
    val preimage: String? = null,
    val tlvRecords: List<KeysendTlvRecord> = emptyList()
)

data class MultiKeysendItem(
    val id: String? = null,
    val destinationPubkey: String,
    val amount: BitcoinAmount,
    val preimage: String? = null,
    val tlvRecords: List<KeysendTlvRecord> = emptyList()
)

data class KeysendTlvRecord(
    val type: Long,
    val valueHex: String
)

data class MakeInvoiceParams(
    val amount: BitcoinAmount,
    val description: String? = null,
    val descriptionHash: String? = null,
    val expirySeconds: Long? = null,
    val metadata: JsonObject? = null
)

data class LookupInvoiceParams(
    val paymentHash: String? = null,
    val invoice: String? = null
) {
    init {
        require(!paymentHash.isNullOrBlank() || !invoice.isNullOrBlank()) {
            "lookup_invoice requires either paymentHash or invoice"
        }
    }
}

data class ListTransactionsParams(
    val fromTimestamp: Long? = null,
    val untilTimestamp: Long? = null,
    val limit: Int? = null,
    val offset: Int? = null,
    val includeUnpaidInvoices: Boolean = false,
    val type: TransactionType? = null
)
