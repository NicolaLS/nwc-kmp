package io.github.nostr.nwc.testing

import io.github.nostr.nwc.internal.MethodNames
import io.github.nostr.nwc.internal.jsonObjectOrNull
import io.github.nostr.nwc.internal.string
import io.github.nostr.nwc.model.BitcoinAmount
import io.github.nostr.nwc.model.NwcError
import io.github.nostr.nwc.model.PayInvoiceResult
import io.github.nostr.nwc.model.Transaction
import io.github.nostr.nwc.model.TransactionState
import io.github.nostr.nwc.model.TransactionType
import io.github.nostr.nwc.model.WalletNotification
import io.github.nostr.nwc.model.RawResponse
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.random.Random
import nostr.core.utils.toHexLower

/**
 * Stateful scripted wallet used by integration tests to emulate a NIP-47 responder.
 *
 * The harness keeps a ledger of generated [Transaction]s, emits wallet notifications,
 * and allows consumers to enqueue custom responses per request type.
 */
class ScriptedWalletHarness {
    private val payInvoiceScripts = ArrayDeque<(PayInvoiceRequest) -> ScriptedResponse<PayInvoiceResult>>()
    private val makeInvoiceScripts = ArrayDeque<(MakeInvoiceRequest) -> ScriptedResponse<Transaction>>()
    private val listTransactionsScripts = ArrayDeque<(ListTransactionsRequest) -> ScriptedResponse<List<Transaction>>>()

    private val transactions = mutableListOf<Transaction>()
    private val notifications = mutableListOf<WalletNotification>()

    private var timestampCursor: Long = 1_700_000_000L

    /**
     * Handle an incoming wallet connect request.
     *
     * Only [MethodNames.PAY_INVOICE], [MethodNames.MAKE_INVOICE], and [MethodNames.LIST_TRANSACTIONS]
     * are supported. Unknown methods return an error response.
     */
    fun handle(method: String, params: JsonObject): RawResponse {
        return when (method) {
            MethodNames.PAY_INVOICE -> handlePayInvoice(params)
            MethodNames.MAKE_INVOICE -> handleMakeInvoice(params)
            MethodNames.LIST_TRANSACTIONS -> handleListTransactions(params)
            else -> failureResponse(NwcError("UNSUPPORTED_METHOD", "Harness cannot handle $method"))
        }
    }

    /**
     * Enqueue a scripted response for the next [MethodNames.PAY_INVOICE] request.
     */
    fun enqueuePayInvoice(handler: (PayInvoiceRequest) -> ScriptedResponse<PayInvoiceResult>) {
        payInvoiceScripts.addLast(handler)
    }

    /**
     * Enqueue a successful [MethodNames.PAY_INVOICE] response.
     */
    fun enqueuePayInvoiceSuccess(
        verify: (PayInvoiceRequest) -> Unit = {},
        buildResult: (PayInvoiceRequest) -> PayInvoiceResult = { defaultPayInvoiceResult(it) }
    ) {
        enqueuePayInvoice { request ->
            verify(request)
            ScriptedResponse.Success(buildResult(request))
        }
    }

    /**
     * Enqueue a failed [MethodNames.PAY_INVOICE] response.
     */
    fun enqueuePayInvoiceError(
        error: NwcError,
        verify: (PayInvoiceRequest) -> Unit = {}
    ) {
        enqueuePayInvoice { request ->
            verify(request)
            ScriptedResponse.Failure(error)
        }
    }

    /**
     * Enqueue a scripted response for the next [MethodNames.MAKE_INVOICE] request.
     */
    fun enqueueMakeInvoice(handler: (MakeInvoiceRequest) -> ScriptedResponse<Transaction>) {
        makeInvoiceScripts.addLast(handler)
    }

    fun enqueueMakeInvoiceSuccess(
        verify: (MakeInvoiceRequest) -> Unit = {},
        buildTransaction: (MakeInvoiceRequest) -> Transaction = { defaultInvoiceTransaction(it) }
    ) {
        enqueueMakeInvoice { request ->
            verify(request)
            ScriptedResponse.Success(buildTransaction(request))
        }
    }

    fun enqueueMakeInvoiceError(
        error: NwcError,
        verify: (MakeInvoiceRequest) -> Unit = {}
    ) {
        enqueueMakeInvoice { request ->
            verify(request)
            ScriptedResponse.Failure(error)
        }
    }

    /**
     * Enqueue a scripted response for the next [MethodNames.LIST_TRANSACTIONS] request.
     */
    fun enqueueListTransactions(handler: (ListTransactionsRequest) -> ScriptedResponse<List<Transaction>>) {
        listTransactionsScripts.addLast(handler)
    }

    /**
     * Returns a snapshot of the ledger maintained by the harness.
     */
    fun recordedTransactions(): List<Transaction> = transactions.toList()

    /**
     * Returns notifications that were emitted while handling requests.
     */
    fun recordedNotifications(): List<WalletNotification> = notifications.toList()

    /**
     * Clears previously recorded transactions and notifications.
     */
    fun reset() {
        transactions.clear()
        notifications.clear()
        timestampCursor = 1_700_000_000L
    }

    /**
     * Allows tests to manipulate the monotonically increasing timestamp used by the harness.
     */
    fun setTimestampCursor(value: Long) {
        timestampCursor = value
    }

    private fun handlePayInvoice(params: JsonObject): RawResponse {
        val request = parsePayInvoiceRequest(params)
            ?: return invalidParams("pay_invoice", "invoice")
        val responder = payInvoiceScripts.removeFirstOrNull() ?: { ScriptedResponse.Success(defaultPayInvoiceResult(it)) }
        return when (val response = responder(request)) {
            is ScriptedResponse.Success -> {
                val result = response.value
                recordOutgoingPayment(request, result)
                successResponse(result.toJson())
            }
            is ScriptedResponse.Failure -> failureResponse(response.error)
        }
    }

    private fun handleMakeInvoice(params: JsonObject): RawResponse {
        val request = parseMakeInvoiceRequest(params) ?: return invalidParams("make_invoice", "amount")
        val responder = makeInvoiceScripts.removeFirstOrNull() ?: { ScriptedResponse.Success(defaultInvoiceTransaction(it)) }
        return when (val response = responder(request)) {
            is ScriptedResponse.Success -> {
                recordTransaction(response.value)
                successResponse(response.value.toJson())
            }
            is ScriptedResponse.Failure -> failureResponse(response.error)
        }
    }

    private fun handleListTransactions(params: JsonObject): RawResponse {
        val request = parseListTransactionsRequest(params) ?: return invalidParams("list_transactions", null)
        val responder = listTransactionsScripts.removeFirstOrNull()
        val list = when (responder) {
            null -> defaultListTransactions(request)
            else -> when (val response = responder(request)) {
                is ScriptedResponse.Success -> response.value
                is ScriptedResponse.Failure -> return failureResponse(response.error)
            }
        }
        val payload = buildJsonObject {
            put("transactions", buildJsonArray {
                list.forEach { add(it.toJson()) }
            })
        }
        return successResponse(payload)
    }

    private fun parsePayInvoiceRequest(params: JsonObject): PayInvoiceRequest? {
        val invoice = params.string("invoice") ?: return null
        val amount = params["amount"]?.jsonPrimitive?.longOrNull?.let { BitcoinAmount.fromMsats(it) }
        val metadata = params.jsonObjectOrNull("metadata")
        return PayInvoiceRequest(
            invoice = invoice,
            amount = amount,
            metadata = metadata
        )
    }

    private fun parseMakeInvoiceRequest(params: JsonObject): MakeInvoiceRequest? {
        val amountMsats = params["amount"]?.jsonPrimitive?.longOrNull ?: return null
        val amount = BitcoinAmount.fromMsats(amountMsats)
        return MakeInvoiceRequest(
            amount = amount,
            description = params.string("description"),
            descriptionHash = params.string("description_hash"),
            expirySeconds = params["expiry"]?.jsonPrimitive?.longOrNull,
            metadata = params.jsonObjectOrNull("metadata")
        )
    }

    private fun parseListTransactionsRequest(params: JsonObject): ListTransactionsRequest? {
        val typeValue = params.string("type")
        val type = typeValue?.let { TransactionType.fromWire(it) }
        val includeUnpaid = params["unpaid"]?.jsonPrimitive?.booleanOrNull ?: false
        return ListTransactionsRequest(
            fromTimestamp = params["from"]?.jsonPrimitive?.longOrNull,
            untilTimestamp = params["until"]?.jsonPrimitive?.longOrNull,
            limit = params["limit"]?.jsonPrimitive?.intOrNull,
            offset = params["offset"]?.jsonPrimitive?.intOrNull,
            includeUnpaidInvoices = includeUnpaid,
            type = type
        )
    }

    private fun recordOutgoingPayment(request: PayInvoiceRequest, result: PayInvoiceResult) {
        val transaction = Transaction(
            type = TransactionType.OUTGOING,
            state = TransactionState.SETTLED,
            invoice = request.invoice,
            description = request.metadata?.string("description"),
            descriptionHash = null,
            preimage = result.preimage,
            paymentHash = randomHex(32),
            amount = request.amount ?: BitcoinAmount.fromMsats(0),
            feesPaid = result.feesPaid,
            createdAt = nextTimestamp(),
            expiresAt = null,
            settledAt = nextTimestamp(),
            metadata = request.metadata
        )
        recordTransaction(transaction)
        notifications += WalletNotification.PaymentSent(transaction)
    }

    private fun recordTransaction(transaction: Transaction) {
        transactions.add(transaction)
    }

    private fun defaultPayInvoiceResult(request: PayInvoiceRequest): PayInvoiceResult {
        val fees = request.amount?.let { estimateFee(it) }
        return PayInvoiceResult(
            preimage = randomHex(32),
            feesPaid = fees
        )
    }

    private fun defaultInvoiceTransaction(request: MakeInvoiceRequest): Transaction {
        val created = nextTimestamp()
        return Transaction(
            type = TransactionType.INCOMING,
            state = TransactionState.PENDING,
            invoice = "lnbc-scripted-${randomHex(8)}",
            description = request.description,
            descriptionHash = request.descriptionHash,
            preimage = null,
            paymentHash = randomHex(32),
            amount = request.amount,
            feesPaid = null,
            createdAt = created,
            expiresAt = request.expirySeconds?.let { created + it },
            settledAt = null,
            metadata = request.metadata
        )
    }

    private fun defaultListTransactions(request: ListTransactionsRequest): List<Transaction> {
        var list = transactions.toList()
        request.type?.let { desired ->
            list = list.filter { it.type == desired }
        }
        request.fromTimestamp?.let { lower ->
            list = list.filter { it.createdAt >= lower }
        }
        request.untilTimestamp?.let { upper ->
            list = list.filter { it.createdAt <= upper }
        }
        if (!request.includeUnpaidInvoices) {
            list = list.filterNot { it.state == TransactionState.PENDING }
        }
        request.offset?.let { offset ->
            list = list.drop(offset)
        }
        request.limit?.let { limit ->
            list = list.take(limit)
        }
        return list
    }

    private fun nextTimestamp(): Long = timestampCursor++

    private fun estimateFee(amount: BitcoinAmount): BitcoinAmount? {
        val fee = (amount.msats * 5) / 10_000 // 0.05 %
        return if (fee <= 0) null else BitcoinAmount.fromMsats(fee)
    }

    private fun successResponse(result: JsonElement): RawResponse =
        RawResponse(resultType = "result", result = result, error = null)

    private fun failureResponse(error: NwcError): RawResponse =
        RawResponse(resultType = "error", result = JsonNull, error = error)

    private fun invalidParams(method: String, field: String?): RawResponse {
        val message = if (field != null) {
            "$method request missing $field"
        } else {
            "$method request contains invalid parameters"
        }
        return failureResponse(NwcError("INVALID_PARAMS", message))
    }

    private fun Transaction.toJson(): JsonObject = buildJsonObject {
        put("type", type.toWire())
        state?.let { put("state", it.name.lowercase()) }
        invoice?.let { put("invoice", it) }
        description?.let { put("description", it) }
        descriptionHash?.let { put("description_hash", it) }
        preimage?.let { put("preimage", it) }
        put("payment_hash", paymentHash)
        put("amount", amount.msats)
        feesPaid?.let { put("fees_paid", it.msats) }
        put("created_at", createdAt)
        expiresAt?.let { put("expires_at", it) }
        settledAt?.let { put("settled_at", it) }
        metadata?.let { put("metadata", it) }
    }

    private fun PayInvoiceResult.toJson(): JsonObject = buildJsonObject {
        put("preimage", preimage)
        feesPaid?.let { put("fees_paid", it.msats) }
    }

    private fun randomHex(bytes: Int): String {
        val buffer = ByteArray(bytes)
        Random.nextBytes(buffer)
        return buffer.toHexLower()
    }

    data class PayInvoiceRequest(
        val invoice: String,
        val amount: BitcoinAmount?,
        val metadata: JsonObject?
    )

    data class MakeInvoiceRequest(
        val amount: BitcoinAmount,
        val description: String?,
        val descriptionHash: String?,
        val expirySeconds: Long?,
        val metadata: JsonObject?
    )

    data class ListTransactionsRequest(
        val fromTimestamp: Long?,
        val untilTimestamp: Long?,
        val limit: Int?,
        val offset: Int?,
        val includeUnpaidInvoices: Boolean,
        val type: TransactionType?
    )

    sealed class ScriptedResponse<out T> {
        data class Success<T>(val value: T) : ScriptedResponse<T>()
        data class Failure(val error: NwcError) : ScriptedResponse<Nothing>()
    }
}
