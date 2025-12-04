package io.github.nostr.nwc.internal

import io.github.nostr.nwc.NwcProtocolException
import io.github.nostr.nwc.model.BitcoinAmount
import io.github.nostr.nwc.model.EncryptionScheme
import io.github.nostr.nwc.model.NwcCapability
import io.github.nostr.nwc.model.NwcError
import io.github.nostr.nwc.model.NwcNotificationType
import io.github.nostr.nwc.model.RawResponse
import io.github.nostr.nwc.model.Transaction
import io.github.nostr.nwc.model.TransactionState
import io.github.nostr.nwc.model.TransactionType
import io.github.nostr.nwc.model.WalletMetadata
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import nostr.core.model.Event

/**
 * Parses a raw JSON response into an NwcError if present.
 */
internal fun parseNwcError(element: JsonElement?): NwcError? {
    if (element == null || element is JsonNull) return null
    val obj = element as? JsonObject ?: return null
    val code = obj.string("code") ?: return null
    val message = obj.string("message") ?: ""
    return NwcError(code, message)
}

/**
 * Parses a Transaction from a JSON object.
 */
internal fun parseTransaction(source: JsonObject): Transaction {
    val type = TransactionType.fromWire(source.string("type"))
        ?: throw NwcProtocolException("Transaction missing type")
    val paymentHash = source.string("payment_hash")
        ?: throw NwcProtocolException("Transaction missing payment_hash")
    val amountMsats = source["amount"]?.jsonPrimitive?.longOrNull
        ?: throw NwcProtocolException("Transaction missing amount")
    val createdAt = source["created_at"]?.jsonPrimitive?.longOrNull
        ?: throw NwcProtocolException("Transaction missing created_at")
    val state = TransactionState.fromWire(source.string("state"))
    val feesPaid = source["fees_paid"]?.jsonPrimitive?.longOrNull?.let { BitcoinAmount.fromMsats(it) }
    val metadata = source.jsonObjectOrNull("metadata")
    return Transaction(
        type = type,
        state = state,
        invoice = source.string("invoice"),
        description = source.string("description"),
        descriptionHash = source.string("description_hash"),
        preimage = source.string("preimage"),
        paymentHash = paymentHash,
        amount = BitcoinAmount.fromMsats(amountMsats),
        feesPaid = feesPaid,
        createdAt = createdAt,
        expiresAt = source["expires_at"]?.jsonPrimitive?.longOrNull,
        settledAt = source["settled_at"]?.jsonPrimitive?.longOrNull,
        metadata = metadata
    )
}

/**
 * Parses wallet metadata from an info event.
 */
internal fun parseWalletMetadata(event: Event): WalletMetadata {
    val capabilityValues = event.content
        .split(' ', '\n', '\t')
        .mapNotNull { it.trim().takeIf { it.isNotEmpty() } }
    val capabilities = NwcCapability.parseAll(capabilityValues)
    val encryptionInfo = parseEncryptionTagValues(event.tagValues(TAG_ENCRYPTION))
    val encryptionSchemes = when {
        encryptionInfo.schemes.isEmpty() && encryptionInfo.defaultedToNip04 -> setOf(EncryptionScheme.Nip04)
        encryptionInfo.schemes.isNotEmpty() -> encryptionInfo.schemes.toSet()
        else -> emptySet()
    }
    val notificationsTag = event.tags.firstOrNull { it.firstOrNull() == "notifications" }
    val notificationValues = notificationsTag?.getOrNull(1)
        ?.split(' ')
        ?.mapNotNull { it.trim().takeIf { it.isNotEmpty() } }
        ?: emptyList()
    val notificationTypes = NwcNotificationType.parseAll(notificationValues)
    return WalletMetadata(capabilities, encryptionSchemes, notificationTypes, encryptionInfo.defaultedToNip04)
}

/**
 * Decodes a raw response from JSON element.
 */
internal fun decodeRawResponse(obj: JsonObject): RawResponse {
    val resultType = obj["result_type"]?.jsonPrimitive?.content
        ?: throw NwcProtocolException("Response missing result_type")
    val error = parseNwcError(obj["error"])
    val result = obj["result"]
    return RawResponse(resultType, result, error)
}

// Event tag helpers
internal fun Event.tagValue(name: String): String? =
    tags.firstOrNull { it.isNotEmpty() && it[0] == name }?.getOrNull(1)

internal fun Event.tagValues(name: String): List<String>? =
    tags.firstOrNull { it.isNotEmpty() && it[0] == name }
        ?.drop(1)
        ?.takeIf { it.isNotEmpty() }
