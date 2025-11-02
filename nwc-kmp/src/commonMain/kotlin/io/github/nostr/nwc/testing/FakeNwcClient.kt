package io.github.nostr.nwc.testing

import io.github.nostr.nwc.NwcClientContract
import io.github.nostr.nwc.model.BalanceResult
import io.github.nostr.nwc.model.GetInfoResult
import io.github.nostr.nwc.model.KeysendParams
import io.github.nostr.nwc.model.KeysendResult
import io.github.nostr.nwc.model.ListTransactionsParams
import io.github.nostr.nwc.model.LookupInvoiceParams
import io.github.nostr.nwc.model.MakeInvoiceParams
import io.github.nostr.nwc.model.MultiKeysendItem
import io.github.nostr.nwc.model.MultiPayInvoiceItem
import io.github.nostr.nwc.model.MultiResult
import io.github.nostr.nwc.model.NwcFailure
import io.github.nostr.nwc.model.NwcResult
import io.github.nostr.nwc.model.NwcWalletDescriptor
import io.github.nostr.nwc.model.PayInvoiceParams
import io.github.nostr.nwc.model.PayInvoiceResult
import io.github.nostr.nwc.model.Transaction
import io.github.nostr.nwc.model.WalletMetadata
import io.github.nostr.nwc.model.WalletNotification
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lightweight in-memory implementation of [NwcClientContract] for consumer unit tests.
 *
 * Every request tracks the supplied arguments and returns either a queued override or a configurable
 * default response. Defaults emit a descriptive [NwcFailure.Unknown] so missing stubs are easy to spot.
 */
class FakeNwcClient(
    initialMetadata: WalletMetadata? = null
) : NwcClientContract {

    override val notifications: SharedFlow<WalletNotification>
        get() = notificationsInternal.asSharedFlow()

    override val walletMetadata: StateFlow<WalletMetadata?>
        get() = walletMetadataInternal.asStateFlow()

    private val notificationsInternal = MutableSharedFlow<WalletNotification>(replay = 0, extraBufferCapacity = 16)
    private val walletMetadataInternal = MutableStateFlow(initialMetadata)

    val refreshWalletMetadataCalls = mutableListOf<Long>()
    val getBalanceCalls = mutableListOf<Long>()
    val getInfoCalls = mutableListOf<Long>()
    val payInvoiceCalls = mutableListOf<PayInvoiceCall>()
    val multiPayInvoiceCalls = mutableListOf<MultiPayInvoiceCall>()
    val payKeysendCalls = mutableListOf<PayKeysendCall>()
    val multiPayKeysendCalls = mutableListOf<MultiPayKeysendCall>()
    val makeInvoiceCalls = mutableListOf<MakeInvoiceCall>()
    val lookupInvoiceCalls = mutableListOf<LookupInvoiceCall>()
    val listTransactionsCalls = mutableListOf<ListTransactionsCall>()
    val describeWalletCalls = mutableListOf<Long>()
    var closeCount: Int = 0
        private set

    private val refreshWalletMetadataResults = ArrayDeque<NwcResult<WalletMetadata>>()
    private val getBalanceResults = ArrayDeque<NwcResult<BalanceResult>>()
    private val getInfoResults = ArrayDeque<NwcResult<GetInfoResult>>()
    private val payInvoiceResults = ArrayDeque<NwcResult<PayInvoiceResult>>()
    private val multiPayInvoiceResults = ArrayDeque<NwcResult<Map<String, MultiResult<PayInvoiceResult>>>>()
    private val payKeysendResults = ArrayDeque<NwcResult<KeysendResult>>()
    private val multiPayKeysendResults = ArrayDeque<NwcResult<Map<String, MultiResult<KeysendResult>>>>()
    private val makeInvoiceResults = ArrayDeque<NwcResult<Transaction>>()
    private val lookupInvoiceResults = ArrayDeque<NwcResult<Transaction>>()
    private val listTransactionsResults = ArrayDeque<NwcResult<List<Transaction>>>()
    private val describeWalletResults = ArrayDeque<NwcResult<NwcWalletDescriptor>>()

    var defaultRefreshWalletMetadataResult: NwcResult<WalletMetadata> = missingStub("refreshWalletMetadata")
    var defaultGetBalanceResult: NwcResult<BalanceResult> = missingStub("getBalance")
    var defaultGetInfoResult: NwcResult<GetInfoResult> = missingStub("getInfo")
    var defaultPayInvoiceResult: NwcResult<PayInvoiceResult> = missingStub("payInvoice")
    var defaultMultiPayInvoiceResult: NwcResult<Map<String, MultiResult<PayInvoiceResult>>> =
        missingStub("multiPayInvoice")
    var defaultPayKeysendResult: NwcResult<KeysendResult> = missingStub("payKeysend")
    var defaultMultiPayKeysendResult: NwcResult<Map<String, MultiResult<KeysendResult>>> =
        missingStub("multiPayKeysend")
    var defaultMakeInvoiceResult: NwcResult<Transaction> = missingStub("makeInvoice")
    var defaultLookupInvoiceResult: NwcResult<Transaction> = missingStub("lookupInvoice")
    var defaultListTransactionsResult: NwcResult<List<Transaction>> = missingStub("listTransactions")
    var defaultDescribeWalletResult: NwcResult<NwcWalletDescriptor> = missingStub("describeWallet")

    override suspend fun refreshWalletMetadata(timeoutMillis: Long): NwcResult<WalletMetadata> {
        refreshWalletMetadataCalls += timeoutMillis
        val result = nextResult(refreshWalletMetadataResults, defaultRefreshWalletMetadataResult)
        if (result is NwcResult.Success) {
            walletMetadataInternal.value = result.value
        }
        return result
    }

    override suspend fun getBalance(timeoutMillis: Long): NwcResult<BalanceResult> {
        getBalanceCalls += timeoutMillis
        return nextResult(getBalanceResults, defaultGetBalanceResult)
    }

    override suspend fun getInfo(timeoutMillis: Long): NwcResult<GetInfoResult> {
        getInfoCalls += timeoutMillis
        return nextResult(getInfoResults, defaultGetInfoResult)
    }

    override suspend fun payInvoice(
        params: PayInvoiceParams,
        timeoutMillis: Long
    ): NwcResult<PayInvoiceResult> {
        payInvoiceCalls += PayInvoiceCall(params, timeoutMillis)
        return nextResult(payInvoiceResults, defaultPayInvoiceResult)
    }

    override suspend fun multiPayInvoice(
        invoices: List<MultiPayInvoiceItem>,
        timeoutMillis: Long
    ): NwcResult<Map<String, MultiResult<PayInvoiceResult>>> {
        multiPayInvoiceCalls += MultiPayInvoiceCall(invoices.toList(), timeoutMillis)
        return nextResult(multiPayInvoiceResults, defaultMultiPayInvoiceResult)
    }

    override suspend fun payKeysend(
        params: KeysendParams,
        timeoutMillis: Long
    ): NwcResult<KeysendResult> {
        payKeysendCalls += PayKeysendCall(params, timeoutMillis)
        return nextResult(payKeysendResults, defaultPayKeysendResult)
    }

    override suspend fun multiPayKeysend(
        items: List<MultiKeysendItem>,
        timeoutMillis: Long
    ): NwcResult<Map<String, MultiResult<KeysendResult>>> {
        multiPayKeysendCalls += MultiPayKeysendCall(items.toList(), timeoutMillis)
        return nextResult(multiPayKeysendResults, defaultMultiPayKeysendResult)
    }

    override suspend fun makeInvoice(
        params: MakeInvoiceParams,
        timeoutMillis: Long
    ): NwcResult<Transaction> {
        makeInvoiceCalls += MakeInvoiceCall(params, timeoutMillis)
        return nextResult(makeInvoiceResults, defaultMakeInvoiceResult)
    }

    override suspend fun lookupInvoice(
        params: LookupInvoiceParams,
        timeoutMillis: Long
    ): NwcResult<Transaction> {
        lookupInvoiceCalls += LookupInvoiceCall(params, timeoutMillis)
        return nextResult(lookupInvoiceResults, defaultLookupInvoiceResult)
    }

    override suspend fun listTransactions(
        params: ListTransactionsParams,
        timeoutMillis: Long
    ): NwcResult<List<Transaction>> {
        listTransactionsCalls += ListTransactionsCall(params, timeoutMillis)
        return nextResult(listTransactionsResults, defaultListTransactionsResult)
    }

    override suspend fun describeWallet(timeoutMillis: Long): NwcResult<NwcWalletDescriptor> {
        describeWalletCalls += timeoutMillis
        return nextResult(describeWalletResults, defaultDescribeWalletResult)
    }

    override suspend fun close() {
        closeCount += 1
    }

    suspend fun emitNotification(notification: WalletNotification) {
        notificationsInternal.emit(notification)
    }

    fun tryEmitNotification(notification: WalletNotification): Boolean =
        notificationsInternal.tryEmit(notification)

    fun enqueueRefreshWalletMetadataResult(result: NwcResult<WalletMetadata>) {
        refreshWalletMetadataResults.addLast(result)
    }

    fun enqueueGetBalanceResult(result: NwcResult<BalanceResult>) {
        getBalanceResults.addLast(result)
    }

    fun enqueueGetInfoResult(result: NwcResult<GetInfoResult>) {
        getInfoResults.addLast(result)
    }

    fun enqueuePayInvoiceResult(result: NwcResult<PayInvoiceResult>) {
        payInvoiceResults.addLast(result)
    }

    fun enqueueMultiPayInvoiceResult(result: NwcResult<Map<String, MultiResult<PayInvoiceResult>>>) {
        multiPayInvoiceResults.addLast(result)
    }

    fun enqueuePayKeysendResult(result: NwcResult<KeysendResult>) {
        payKeysendResults.addLast(result)
    }

    fun enqueueMultiPayKeysendResult(result: NwcResult<Map<String, MultiResult<KeysendResult>>>) {
        multiPayKeysendResults.addLast(result)
    }

    fun enqueueMakeInvoiceResult(result: NwcResult<Transaction>) {
        makeInvoiceResults.addLast(result)
    }

    fun enqueueLookupInvoiceResult(result: NwcResult<Transaction>) {
        lookupInvoiceResults.addLast(result)
    }

    fun enqueueListTransactionsResult(result: NwcResult<List<Transaction>>) {
        listTransactionsResults.addLast(result)
    }

    fun enqueueDescribeWalletResult(result: NwcResult<NwcWalletDescriptor>) {
        describeWalletResults.addLast(result)
    }

    fun resetQueues() {
        refreshWalletMetadataResults.clear()
        getBalanceResults.clear()
        getInfoResults.clear()
        payInvoiceResults.clear()
        multiPayInvoiceResults.clear()
        payKeysendResults.clear()
        multiPayKeysendResults.clear()
        makeInvoiceResults.clear()
        lookupInvoiceResults.clear()
        listTransactionsResults.clear()
        describeWalletResults.clear()
    }

    fun resetCalls() {
        refreshWalletMetadataCalls.clear()
        getBalanceCalls.clear()
        getInfoCalls.clear()
        payInvoiceCalls.clear()
        multiPayInvoiceCalls.clear()
        payKeysendCalls.clear()
        multiPayKeysendCalls.clear()
        makeInvoiceCalls.clear()
        lookupInvoiceCalls.clear()
        listTransactionsCalls.clear()
        describeWalletCalls.clear()
        closeCount = 0
    }

    private fun <T> nextResult(
        queue: ArrayDeque<NwcResult<T>>,
        fallback: NwcResult<T>
    ): NwcResult<T> = if (queue.isNotEmpty()) queue.removeFirst() else fallback

    private fun <T> missingStub(method: String): NwcResult<T> =
        NwcResult.Failure(NwcFailure.Unknown("FakeNwcClient: $method not stubbed"))

    data class PayInvoiceCall(val params: PayInvoiceParams, val timeoutMillis: Long)
    data class MultiPayInvoiceCall(val invoices: List<MultiPayInvoiceItem>, val timeoutMillis: Long)
    data class PayKeysendCall(val params: KeysendParams, val timeoutMillis: Long)
    data class MultiPayKeysendCall(val items: List<MultiKeysendItem>, val timeoutMillis: Long)
    data class MakeInvoiceCall(val params: MakeInvoiceParams, val timeoutMillis: Long)
    data class LookupInvoiceCall(val params: LookupInvoiceParams, val timeoutMillis: Long)
    data class ListTransactionsCall(val params: ListTransactionsParams, val timeoutMillis: Long)
}
