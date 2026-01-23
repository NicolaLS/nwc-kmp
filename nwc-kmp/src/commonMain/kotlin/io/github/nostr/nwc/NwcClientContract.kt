package io.github.nostr.nwc

import io.github.nostr.nwc.model.BalanceResult
import io.github.nostr.nwc.model.GetInfoResult
import io.github.nostr.nwc.model.KeysendParams
import io.github.nostr.nwc.model.KeysendResult
import io.github.nostr.nwc.model.ListTransactionsParams
import io.github.nostr.nwc.model.LookupInvoiceParams
import io.github.nostr.nwc.model.MakeInvoiceParams
import io.github.nostr.nwc.model.MultiResult
import io.github.nostr.nwc.model.MultiKeysendItem
import io.github.nostr.nwc.model.MultiPayInvoiceItem
import io.github.nostr.nwc.model.NwcResult
import io.github.nostr.nwc.model.NwcWalletDescriptor
import io.github.nostr.nwc.model.PayInvoiceParams
import io.github.nostr.nwc.model.PayInvoiceResult
import io.github.nostr.nwc.model.Transaction
import io.github.nostr.nwc.model.WalletMetadata
import io.github.nostr.nwc.model.WalletNotification
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Minimal surface area exposed by [NwcClient] and test doubles.
 * Consumers can depend on this contract to enable dependency injection and lightweight fakes.
 *
 * ## Request APIs
 *
 * This contract provides two API styles:
 *
 * **1. Suspending functions with timeout (original API):**
 * - `payInvoice(params, timeoutMillis)` → suspends until result or timeout
 * - Returns [NwcResult] with Success or Failure
 * - Use when you want a simple fire-and-wait pattern
 *
 * **2. Flow-based request handles (new API):**
 * - `payInvoiceRequest(params)` → returns immediately with [NwcRequest]
 * - Observe [NwcRequest.state] for Loading → Success/Failure transitions
 * - No library-enforced timeout - caller decides how long to wait
 * - Call [NwcRequest.cancel] to cleanup when done
 * - Use for slow operations (payments) where you want UI feedback during loading
 */
interface NwcClientContract {
    val notifications: SharedFlow<WalletNotification>
    val walletMetadata: StateFlow<WalletMetadata?>

    suspend fun refreshWalletMetadata(
        timeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS
    ): NwcResult<WalletMetadata>

    suspend fun getBalance(
        timeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS
    ): NwcResult<BalanceResult>

    suspend fun getInfo(
        timeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS
    ): NwcResult<GetInfoResult>

    suspend fun payInvoice(
        params: PayInvoiceParams,
        timeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS
    ): NwcResult<PayInvoiceResult>

    suspend fun multiPayInvoice(
        invoices: List<MultiPayInvoiceItem>,
        timeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS
    ): NwcResult<Map<String, MultiResult<PayInvoiceResult>>>

    suspend fun payKeysend(
        params: KeysendParams,
        timeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS
    ): NwcResult<KeysendResult>

    suspend fun multiPayKeysend(
        items: List<MultiKeysendItem>,
        timeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS
    ): NwcResult<Map<String, MultiResult<KeysendResult>>>

    suspend fun makeInvoice(
        params: MakeInvoiceParams,
        timeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS
    ): NwcResult<Transaction>

    suspend fun lookupInvoice(
        params: LookupInvoiceParams,
        timeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS
    ): NwcResult<Transaction>

    suspend fun listTransactions(
        params: ListTransactionsParams,
        timeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS
    ): NwcResult<List<Transaction>>

    suspend fun describeWallet(
        timeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS
    ): NwcResult<NwcWalletDescriptor>

    suspend fun close()

    // ==================== Flow-based Request API ====================
    //
    // These methods return NwcRequest wrappers for observable request state.
    // Unlike the suspending methods above, they:
    // - Return immediately (non-blocking)
    // - Do not enforce a timeout (caller decides)
    // - Allow observation via StateFlow<NwcRequestState>
    // - Support cancellation for resource cleanup

    /**
     * Initiates a payment and returns an observable request handle.
     * @see payInvoice for the suspending version with timeout
     */
    fun payInvoiceRequest(params: PayInvoiceParams): NwcRequest<PayInvoiceResult>

    /**
     * Initiates a keysend payment and returns an observable request handle.
     * @see payKeysend for the suspending version with timeout
     */
    fun payKeysendRequest(params: KeysendParams): NwcRequest<KeysendResult>

    /**
     * Fetches wallet balance and returns an observable request handle.
     * @see getBalance for the suspending version with timeout
     */
    fun getBalanceRequest(): NwcRequest<BalanceResult>

    /**
     * Fetches wallet info and returns an observable request handle.
     * @see getInfo for the suspending version with timeout
     */
    fun getInfoRequest(): NwcRequest<GetInfoResult>

    /**
     * Creates an invoice and returns an observable request handle.
     * @see makeInvoice for the suspending version with timeout
     */
    fun makeInvoiceRequest(params: MakeInvoiceParams): NwcRequest<Transaction>

    /**
     * Looks up an invoice and returns an observable request handle.
     * @see lookupInvoice for the suspending version with timeout
     */
    fun lookupInvoiceRequest(params: LookupInvoiceParams): NwcRequest<Transaction>

    /**
     * Lists transactions and returns an observable request handle.
     * @see listTransactions for the suspending version with timeout
     */
    fun listTransactionsRequest(params: ListTransactionsParams): NwcRequest<List<Transaction>>
}
