package io.github.nostr.nwc.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.jvm.JvmInline

@JvmInline
value class BitcoinAmount(val millisatoshis: Long) {
    init {
        require(millisatoshis >= 0) { "BitcoinAmount cannot be negative" }
    }

    val msats: Long get() = millisatoshis
    val sats: Long get() = millisatoshis / 1_000

    fun toJsonPrimitive() = millisatoshis

    companion object {
        fun fromMsats(msats: Long): BitcoinAmount = BitcoinAmount(msats)
        fun fromSats(sats: Long): BitcoinAmount = BitcoinAmount(sats * 1_000)
    }
}

data class NwcError(
    val code: String,
    val message: String
)

data class PayInvoiceResult(
    val preimage: String,
    val feesPaid: BitcoinAmount?
)

typealias KeysendResult = PayInvoiceResult

enum class TransactionType {
    INCOMING,
    OUTGOING;

    companion object {
        fun fromWire(value: String?): TransactionType? = when (value?.lowercase()) {
            "incoming" -> INCOMING
            "outgoing" -> OUTGOING
            else -> null
        }
    }
}

enum class TransactionState {
    PENDING,
    SETTLED,
    EXPIRED,
    FAILED;

    companion object {
        fun fromWire(value: String?): TransactionState? = when (value?.lowercase()) {
            "pending" -> PENDING
            "settled" -> SETTLED
            "expired" -> EXPIRED
            "failed" -> FAILED
            else -> null
        }
    }
}

data class Transaction(
    val type: TransactionType,
    val state: TransactionState?,
    val invoice: String?,
    val description: String?,
    val descriptionHash: String?,
    val preimage: String?,
    val paymentHash: String,
    val amount: BitcoinAmount,
    val feesPaid: BitcoinAmount?,
    val createdAt: Long,
    val expiresAt: Long?,
    val settledAt: Long?,
    val metadata: JsonObject?
)

data class BalanceResult(
    val balance: BitcoinAmount
)

sealed class NwcCapability(val wireName: String) {
    object PayInvoice : NwcCapability("pay_invoice")
    object MultiPayInvoice : NwcCapability("multi_pay_invoice")
    object PayKeysend : NwcCapability("pay_keysend")
    object MultiPayKeysend : NwcCapability("multi_pay_keysend")
    object MakeInvoice : NwcCapability("make_invoice")
    object LookupInvoice : NwcCapability("lookup_invoice")
    object ListTransactions : NwcCapability("list_transactions")
    object GetBalance : NwcCapability("get_balance")
    object GetInfo : NwcCapability("get_info")
    data class Unknown(val value: String) : NwcCapability(value)

    companion object {
        private val known = listOf(
            PayInvoice,
            MultiPayInvoice,
            PayKeysend,
            MultiPayKeysend,
            MakeInvoice,
            LookupInvoice,
            ListTransactions,
            GetBalance,
            GetInfo
        ).associateBy { it.wireName }

        fun fromWire(raw: String?): NwcCapability? {
            if (raw.isNullOrBlank()) return null
            val normalized = raw.trim().lowercase()
            return known[normalized] ?: Unknown(normalized)
        }

        fun parseAll(values: Collection<String>): Set<NwcCapability> =
            values.mapNotNull { fromWire(it) }.toSet()
    }
}

sealed class NwcNotificationType(val wireName: String) {
    object PaymentReceived : NwcNotificationType("payment_received")
    object PaymentSent : NwcNotificationType("payment_sent")
    data class Unknown(val value: String) : NwcNotificationType(value)

    companion object {
        private val known = listOf(PaymentReceived, PaymentSent).associateBy { it.wireName }

        fun fromWire(raw: String?): NwcNotificationType? {
            if (raw.isNullOrBlank()) return null
            val normalized = raw.trim().lowercase()
            return known[normalized] ?: Unknown(normalized)
        }

        fun parseAll(values: Collection<String>): Set<NwcNotificationType> =
            values.mapNotNull { fromWire(it) }.toSet()
    }
}

enum class Network {
    MAINNET,
    TESTNET,
    SIGNET,
    REGTEST,
    UNKNOWN;

    companion object {
        fun fromWire(value: String?): Network = when (value?.lowercase()) {
            "mainnet" -> MAINNET
            "testnet" -> TESTNET
            "signet" -> SIGNET
            "regtest" -> REGTEST
            else -> UNKNOWN
        }
    }
}

data class GetInfoResult(
    val alias: String?,
    val color: String?,
    val pubkey: String,
    val network: Network,
    val blockHeight: Long?,
    val blockHash: String?,
    val methods: Set<NwcCapability>,
    val notifications: Set<NwcNotificationType>
)

sealed class EncryptionScheme(val wireName: String) {
    object Nip44V2 : EncryptionScheme("nip44_v2")
    object Nip04 : EncryptionScheme("nip04")
    data class Unknown(val value: String) : EncryptionScheme(value)

    companion object {
        fun fromWire(raw: String?): EncryptionScheme? {
            if (raw.isNullOrBlank()) return null
            val normalized = raw.trim().lowercase()
            return when (normalized) {
                Nip44V2.wireName -> Nip44V2
                Nip04.wireName -> Nip04
                else -> Unknown(normalized)
            }
        }

        fun parseList(raw: String?): Set<EncryptionScheme> {
            if (raw.isNullOrBlank()) return emptySet()
            return raw.split(' ')
                .mapNotNull { fromWire(it) }
                .toSet()
        }
    }
}

data class WalletMetadata(
    val capabilities: Set<NwcCapability>,
    val encryptionSchemes: Set<EncryptionScheme>,
    val notificationTypes: Set<NwcNotificationType>,
    val encryptionDefaultedToNip04: Boolean = false
)

sealed class WalletNotification {
    abstract val transaction: Transaction

    data class PaymentReceived(override val transaction: Transaction) : WalletNotification()
    data class PaymentSent(override val transaction: Transaction) : WalletNotification()
}

sealed class MultiResult<out T> {
    data class Success<T>(val value: T) : MultiResult<T>()
    data class Failure(val error: NwcError) : MultiResult<Nothing>()
}

data class RawResponse(
    val resultType: String,
    val result: JsonElement?,
    val error: NwcError?
)
