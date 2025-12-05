package io.github.nostr.nwc

import io.github.nostr.nwc.model.BalanceResult
import io.github.nostr.nwc.model.GetInfoResult
import io.github.nostr.nwc.model.KeysendParams
import io.github.nostr.nwc.model.KeysendResult
import io.github.nostr.nwc.model.ListTransactionsParams
import io.github.nostr.nwc.model.LookupInvoiceParams
import io.github.nostr.nwc.model.MakeInvoiceParams
import io.github.nostr.nwc.model.NwcResult
import io.github.nostr.nwc.model.PayInvoiceParams
import io.github.nostr.nwc.model.PayInvoiceResult
import io.github.nostr.nwc.model.Transaction
import io.github.nostr.nwc.model.WalletMetadata
import io.github.nostr.nwc.model.WalletNotification
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * Contract for [NwcWallet] that enables dependency injection and testing with fakes.
 *
 * All request methods return immediately with a flow in Loading state.
 * Client initialization and recovery happens internally without blocking callers.
 */
interface NwcWalletContract {
    /**
     * The original NWC URI string.
     */
    val uri: String

    /**
     * Observable wallet metadata. Updated when the client connects and fetches metadata.
     * May be null if the client hasn't connected yet.
     */
    val walletMetadata: StateFlow<WalletMetadata?>

    /**
     * Observable wallet notifications (payment received/sent events).
     */
    val notifications: SharedFlow<WalletNotification>

    // ==================== Flow-based Request API ====================

    /**
     * Pay a Lightning invoice. Returns immediately with Loading flow.
     */
    fun payInvoice(params: PayInvoiceParams): NwcRequest<PayInvoiceResult>

    /**
     * Pay via keysend. Returns immediately with Loading flow.
     */
    fun payKeysend(params: KeysendParams): NwcRequest<KeysendResult>

    /**
     * Get wallet balance. Returns immediately with Loading flow.
     */
    fun getBalance(): NwcRequest<BalanceResult>

    /**
     * Get wallet info. Returns immediately with Loading flow.
     */
    fun getInfo(): NwcRequest<GetInfoResult>

    /**
     * Create a new invoice. Returns immediately with Loading flow.
     */
    fun makeInvoice(params: MakeInvoiceParams): NwcRequest<Transaction>

    /**
     * Look up an invoice. Returns immediately with Loading flow.
     */
    fun lookupInvoice(params: LookupInvoiceParams): NwcRequest<Transaction>

    /**
     * List transactions. Returns immediately with Loading flow.
     */
    fun listTransactions(params: ListTransactionsParams): NwcRequest<List<Transaction>>

    // ==================== Suspend-based API ====================

    /**
     * Pay a Lightning invoice and wait for the result.
     */
    suspend fun payInvoiceAndWait(params: PayInvoiceParams, timeout: Duration): NwcResult<PayInvoiceResult>

    /**
     * Get wallet balance and wait for the result.
     */
    suspend fun getBalanceAndWait(timeout: Duration): NwcResult<BalanceResult>

    /**
     * Get wallet info and wait for the result.
     */
    suspend fun getInfoAndWait(timeout: Duration): NwcResult<GetInfoResult>

    /**
     * Create an invoice and wait for the result.
     */
    suspend fun makeInvoiceAndWait(params: MakeInvoiceParams, timeout: Duration): NwcResult<Transaction>

    // ==================== Lifecycle ====================

    /**
     * Close the wallet connection and release resources.
     */
    suspend fun close()
}
