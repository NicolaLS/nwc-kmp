package io.github.nostr.nwc.testing

import io.github.nostr.nwc.NwcClientContract
import io.github.nostr.nwc.NwcRequest
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
import io.github.nostr.nwc.model.NwcRequestState
import io.github.nostr.nwc.model.NwcResult
import io.github.nostr.nwc.model.NwcWalletDescriptor
import io.github.nostr.nwc.model.PayInvoiceParams
import io.github.nostr.nwc.model.PayInvoiceResult
import io.github.nostr.nwc.model.Transaction
import io.github.nostr.nwc.model.WalletMetadata
import io.github.nostr.nwc.model.WalletNotification
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

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
        get() = _notifications.asSharedFlow()

    override val walletMetadata: StateFlow<WalletMetadata?>
        get() = _walletMetadata.asStateFlow()

    private val _notifications = MutableSharedFlow<WalletNotification>(replay = 0, extraBufferCapacity = 16)
    private val _walletMetadata = MutableStateFlow(initialMetadata)

    // Generic stub for methods that only take timeout
    class TimeoutStub<R>(name: String) {
        val calls = mutableListOf<Long>()
        private val results = ArrayDeque<NwcResult<R>>()
        var default: NwcResult<R> = NwcResult.Failure(NwcFailure.Unknown("FakeNwcClient: $name not stubbed"))

        fun record(timeoutMillis: Long): NwcResult<R> {
            calls += timeoutMillis
            return results.removeFirstOrNull() ?: default
        }

        fun enqueue(result: NwcResult<R>) = results.addLast(result)
        fun reset() { calls.clear(); results.clear() }
    }

    // Generic stub for methods with params
    class ParamsStub<P, R>(name: String) {
        val calls = mutableListOf<Pair<P, Long>>()
        private val results = ArrayDeque<NwcResult<R>>()
        var default: NwcResult<R> = NwcResult.Failure(NwcFailure.Unknown("FakeNwcClient: $name not stubbed"))

        fun record(params: P, timeoutMillis: Long): NwcResult<R> {
            calls += params to timeoutMillis
            return results.removeFirstOrNull() ?: default
        }

        fun enqueue(result: NwcResult<R>) = results.addLast(result)
        fun reset() { calls.clear(); results.clear() }
    }

    // Stubs for timeout-only methods
    val refreshWalletMetadata = TimeoutStub<WalletMetadata>("refreshWalletMetadata")
    val getBalance = TimeoutStub<BalanceResult>("getBalance")
    val getInfo = TimeoutStub<GetInfoResult>("getInfo")
    val describeWallet = TimeoutStub<NwcWalletDescriptor>("describeWallet")

    // Stubs for parameterized methods
    val payInvoice = ParamsStub<PayInvoiceParams, PayInvoiceResult>("payInvoice")
    val multiPayInvoice = ParamsStub<List<MultiPayInvoiceItem>, Map<String, MultiResult<PayInvoiceResult>>>("multiPayInvoice")
    val payKeysend = ParamsStub<KeysendParams, KeysendResult>("payKeysend")
    val multiPayKeysend = ParamsStub<List<MultiKeysendItem>, Map<String, MultiResult<KeysendResult>>>("multiPayKeysend")
    val makeInvoice = ParamsStub<MakeInvoiceParams, Transaction>("makeInvoice")
    val lookupInvoice = ParamsStub<LookupInvoiceParams, Transaction>("lookupInvoice")
    val listTransactions = ParamsStub<ListTransactionsParams, List<Transaction>>("listTransactions")

    var closeCount: Int = 0
        private set

    override suspend fun refreshWalletMetadata(timeoutMillis: Long): NwcResult<WalletMetadata> {
        val result = refreshWalletMetadata.record(timeoutMillis)
        if (result is NwcResult.Success) _walletMetadata.value = result.value
        return result
    }

    override suspend fun getBalance(timeoutMillis: Long) = getBalance.record(timeoutMillis)
    override suspend fun getInfo(timeoutMillis: Long) = getInfo.record(timeoutMillis)
    override suspend fun describeWallet(timeoutMillis: Long) = describeWallet.record(timeoutMillis)

    override suspend fun payInvoice(params: PayInvoiceParams, timeoutMillis: Long) =
        payInvoice.record(params, timeoutMillis)

    override suspend fun multiPayInvoice(invoices: List<MultiPayInvoiceItem>, timeoutMillis: Long) =
        multiPayInvoice.record(invoices.toList(), timeoutMillis)

    override suspend fun payKeysend(params: KeysendParams, timeoutMillis: Long) =
        payKeysend.record(params, timeoutMillis)

    override suspend fun multiPayKeysend(items: List<MultiKeysendItem>, timeoutMillis: Long) =
        multiPayKeysend.record(items.toList(), timeoutMillis)

    override suspend fun makeInvoice(params: MakeInvoiceParams, timeoutMillis: Long) =
        makeInvoice.record(params, timeoutMillis)

    override suspend fun lookupInvoice(params: LookupInvoiceParams, timeoutMillis: Long) =
        lookupInvoice.record(params, timeoutMillis)

    override suspend fun listTransactions(params: ListTransactionsParams, timeoutMillis: Long) =
        listTransactions.record(params, timeoutMillis)

    override suspend fun close() { closeCount++ }

    suspend fun emitNotification(notification: WalletNotification) = _notifications.emit(notification)
    fun tryEmitNotification(notification: WalletNotification) = _notifications.tryEmit(notification)

    fun reset() {
        refreshWalletMetadata.reset()
        getBalance.reset()
        getInfo.reset()
        describeWallet.reset()
        payInvoice.reset()
        multiPayInvoice.reset()
        payKeysend.reset()
        multiPayKeysend.reset()
        makeInvoice.reset()
        lookupInvoice.reset()
        listTransactions.reset()
        closeCount = 0
    }

    // ==================== Flow-based Request API ====================

    /**
     * Helper to create an NwcRequest from a stubbed result.
     * The request immediately transitions to Success/Failure state.
     */
    private fun <T> createFakeRequest(result: NwcResult<T>): NwcRequest<T> {
        val state = when (result) {
            is NwcResult.Success -> NwcRequestState.Success(result.value)
            is NwcResult.Failure -> NwcRequestState.Failure(result.failure)
        }
        val stateFlow = MutableStateFlow<NwcRequestState<T>>(state)
        val requestId = "fake-${Random.nextLong().toString(16)}"
        return NwcRequest(
            state = stateFlow,
            requestId = requestId,
            job = Job().apply { complete() } // Already completed
        )
    }

    override fun payInvoiceRequest(params: PayInvoiceParams): NwcRequest<PayInvoiceResult> {
        val result = payInvoice.record(params, Long.MAX_VALUE)
        return createFakeRequest(result)
    }

    override fun payKeysendRequest(params: KeysendParams): NwcRequest<KeysendResult> {
        val result = payKeysend.record(params, Long.MAX_VALUE)
        return createFakeRequest(result)
    }

    override fun getBalanceRequest(): NwcRequest<BalanceResult> {
        val result = getBalance.record(Long.MAX_VALUE)
        return createFakeRequest(result)
    }

    override fun getInfoRequest(): NwcRequest<GetInfoResult> {
        val result = getInfo.record(Long.MAX_VALUE)
        return createFakeRequest(result)
    }

    override fun makeInvoiceRequest(params: MakeInvoiceParams): NwcRequest<Transaction> {
        val result = makeInvoice.record(params, Long.MAX_VALUE)
        return createFakeRequest(result)
    }

    override fun lookupInvoiceRequest(params: LookupInvoiceParams): NwcRequest<Transaction> {
        val result = lookupInvoice.record(params, Long.MAX_VALUE)
        return createFakeRequest(result)
    }

    override fun listTransactionsRequest(params: ListTransactionsParams): NwcRequest<List<Transaction>> {
        val result = listTransactions.record(params, Long.MAX_VALUE)
        return createFakeRequest(result)
    }
}
