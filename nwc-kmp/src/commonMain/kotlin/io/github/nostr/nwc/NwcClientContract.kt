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
}
