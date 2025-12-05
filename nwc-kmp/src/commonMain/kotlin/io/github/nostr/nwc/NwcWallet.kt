package io.github.nostr.nwc

import io.github.nostr.nwc.model.BalanceResult
import io.github.nostr.nwc.model.EncryptionScheme
import io.github.nostr.nwc.model.GetInfoResult
import io.github.nostr.nwc.model.KeysendParams
import io.github.nostr.nwc.model.KeysendResult
import io.github.nostr.nwc.model.ListTransactionsParams
import io.github.nostr.nwc.model.LookupInvoiceParams
import io.github.nostr.nwc.model.MakeInvoiceParams
import io.github.nostr.nwc.model.NwcFailure
import io.github.nostr.nwc.model.NwcRequestState
import io.github.nostr.nwc.model.NwcResult
import io.github.nostr.nwc.model.PayInvoiceParams
import io.github.nostr.nwc.model.PayInvoiceResult
import io.github.nostr.nwc.model.Transaction
import io.github.nostr.nwc.model.WalletMetadata
import io.github.nostr.nwc.model.WalletNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

/**
 * High-level wallet connection that never blocks callers.
 *
 * All request methods return immediately with a flow in [NwcRequestState.Loading] state.
 * Client initialization and recovery happens internally without blocking callers.
 *
 * This class provides a simpler API than using [NwcClient] directly when you want:
 * - Non-blocking request methods that always return immediately
 * - Automatic lazy client creation and caching
 * - No need to manage client lifecycle manually
 *
 * Example usage:
 * ```kotlin
 * val wallet = NwcWallet.create(
 *     uri = "nostr+walletconnect://...",
 *     sessionManager = sessionManager,
 *     scope = coroutineScope
 * )
 *
 * // Returns immediately with Loading flow - client creation happens in background
 * val request = wallet.payInvoice(PayInvoiceParams(invoice = "lnbc..."))
 *
 * // Observe the request state
 * request.state.collect { state ->
 *     when (state) {
 *         NwcRequestState.Loading -> showLoading()
 *         is NwcRequestState.Success -> showSuccess(state.value)
 *         is NwcRequestState.Failure -> showError(state.failure)
 *     }
 * }
 *
 * // When done
 * wallet.close()
 * ```
 */
class NwcWallet private constructor(
    /**
     * The original NWC URI string.
     */
    override val uri: String,
    private val credentials: NwcCredentials,
    private val sessionManager: NwcSessionManager,
    private val scope: CoroutineScope,
    private val requestTimeoutMillis: Long,
    private val cachedMetadata: WalletMetadata?,
    private val cachedEncryption: EncryptionScheme?
) : NwcWalletContract {
    private val clientMutex = Mutex()
    private var cachedHandle: ClientHandle? = null
    private var clientCreation: Deferred<ClientHandle>? = null

    private data class ClientHandle(
        val session: NwcSession,
        val client: NwcClient
    )

    /**
     * Observable wallet metadata. Updated when the client connects and fetches metadata.
     * May be null if the client hasn't connected yet.
     */
    override val walletMetadata: StateFlow<WalletMetadata?>
        get() = cachedHandle?.client?.walletMetadata ?: MutableStateFlow(cachedMetadata)

    /**
     * Observable wallet notifications (payment received/sent events).
     * Note: Notifications only flow once a client is created.
     */
    override val notifications: SharedFlow<WalletNotification>
        get() = cachedHandle?.client?.notifications ?: MutableSharedFlow()

    // ==================== Flow-based Request API ====================
    // These methods return immediately with Loading state.
    // Client creation happens in the background.

    /**
     * Pay a Lightning invoice. Returns immediately with Loading flow.
     * Client creation/recovery happens internally.
     */
    override fun payInvoice(params: PayInvoiceParams): NwcRequest<PayInvoiceResult> =
        createDeferredRequest { client -> client.payInvoiceRequest(params) }

    /**
     * Pay via keysend. Returns immediately with Loading flow.
     */
    override fun payKeysend(params: KeysendParams): NwcRequest<KeysendResult> =
        createDeferredRequest { client -> client.payKeysendRequest(params) }

    /**
     * Get wallet balance. Returns immediately with Loading flow.
     */
    override fun getBalance(): NwcRequest<BalanceResult> =
        createDeferredRequest { client -> client.getBalanceRequest() }

    /**
     * Get wallet info. Returns immediately with Loading flow.
     */
    override fun getInfo(): NwcRequest<GetInfoResult> =
        createDeferredRequest { client -> client.getInfoRequest() }

    /**
     * Create a new invoice. Returns immediately with Loading flow.
     */
    override fun makeInvoice(params: MakeInvoiceParams): NwcRequest<Transaction> =
        createDeferredRequest { client -> client.makeInvoiceRequest(params) }

    /**
     * Look up an invoice. Returns immediately with Loading flow.
     */
    override fun lookupInvoice(params: LookupInvoiceParams): NwcRequest<Transaction> =
        createDeferredRequest { client -> client.lookupInvoiceRequest(params) }

    /**
     * List transactions. Returns immediately with Loading flow.
     */
    override fun listTransactions(params: ListTransactionsParams): NwcRequest<List<Transaction>> =
        createDeferredRequest { client -> client.listTransactionsRequest(params) }

    // ==================== Suspend-based API ====================
    // Convenience methods that wait for results.

    /**
     * Pay a Lightning invoice and wait for the result.
     *
     * @param params Invoice payment parameters
     * @param timeout Maximum time to wait for the result
     * @return [NwcResult.Success] with payment result, or [NwcResult.Failure] on error/timeout
     */
    override suspend fun payInvoiceAndWait(
        params: PayInvoiceParams,
        timeout: Duration
    ): NwcResult<PayInvoiceResult> = payInvoice(params).toResult(timeout)

    /**
     * Get wallet balance and wait for the result.
     */
    override suspend fun getBalanceAndWait(timeout: Duration): NwcResult<BalanceResult> =
        getBalance().toResult(timeout)

    /**
     * Get wallet info and wait for the result.
     */
    override suspend fun getInfoAndWait(timeout: Duration): NwcResult<GetInfoResult> =
        getInfo().toResult(timeout)

    /**
     * Create an invoice and wait for the result.
     */
    override suspend fun makeInvoiceAndWait(
        params: MakeInvoiceParams,
        timeout: Duration
    ): NwcResult<Transaction> = makeInvoice(params).toResult(timeout)

    // ==================== Lifecycle ====================

    /**
     * Close the wallet connection and release resources.
     * After calling this, the wallet should not be used.
     */
    override suspend fun close() {
        clientMutex.withLock {
            val handle = cachedHandle
            cachedHandle = null
            clientCreation?.cancel()
            clientCreation = null

            handle?.client?.close()
            handle?.session?.let { sessionManager.release(it) }
        }
    }

    // ==================== Internal ====================

    private fun <T> createDeferredRequest(
        makeRequest: (NwcClient) -> NwcRequest<T>
    ): NwcRequest<T> {
        val stateFlow = MutableStateFlow<NwcRequestState<T>>(NwcRequestState.Loading)

        val job = scope.launch {
            try {
                val handle = ensureClient()
                val innerRequest = makeRequest(handle.client)
                innerRequest.state.collect { state ->
                    stateFlow.value = state
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                stateFlow.value = NwcRequestState.Failure(e.toNwcFailure())
            }
        }

        return NwcRequest(
            state = stateFlow,
            requestId = "deferred-${nextRequestId()}",
            job = job
        )
    }

    private suspend fun ensureClient(): ClientHandle {
        // Fast path: return cached handle if available
        cachedHandle?.let { return it }

        val deferred = clientMutex.withLock {
            // Double-check inside lock
            cachedHandle?.let { return@withLock null }

            // Return existing in-flight creation if one is active
            clientCreation?.takeIf { it.isActive }?.let { return@withLock it }

            // Start new client creation
            val created = scope.async {
                val session = sessionManager.acquire(credentials, autoOpen = false)
                try {
                    val client = NwcClient.createWithSession(
                        session = session,
                        requestTimeoutMillis = requestTimeoutMillis,
                        cachedMetadata = cachedMetadata,
                        cachedEncryption = cachedEncryption
                    )
                    ClientHandle(session, client)
                } catch (e: Throwable) {
                    // Release session if client creation fails
                    runCatching { sessionManager.release(session) }
                    throw e
                }
            }
            clientCreation = created
            created
        }

        if (deferred == null) {
            return cachedHandle ?: ensureClient()
        }

        return try {
            val handle = deferred.await()
            clientMutex.withLock {
                if (cachedHandle == null) {
                    cachedHandle = handle
                }
                clientCreation = null
                cachedHandle ?: handle
            }
        } catch (e: Throwable) {
            clientMutex.withLock {
                if (clientCreation === deferred) {
                    clientCreation = null
                }
            }
            throw e
        }
    }

    private fun Throwable.toNwcFailure(): NwcFailure = when (this) {
        is NwcNetworkException -> NwcFailure.Network(message, throwable = this)
        is NwcTimeoutException -> NwcFailure.Timeout(message)
        is NwcRequestException -> NwcFailure.Wallet(error)
        is NwcProtocolException -> NwcFailure.Protocol(message ?: "Protocol error")
        is NwcEncryptionException -> NwcFailure.EncryptionUnsupported(message ?: "Encryption error")
        else -> NwcFailure.Unknown(message, this)
    }

    companion object {
        private var requestCounter = 0L

        private fun nextRequestId(): Long = ++requestCounter

        /**
         * Create a new NwcWallet from a NWC URI string.
         *
         * The wallet is created immediately but client initialization is deferred
         * until the first request is made. This ensures the factory method never blocks.
         *
         * @param uri NWC connection URI string (nostr+walletconnect://...)
         * @param sessionManager Session manager for pooling relay connections
         * @param scope CoroutineScope for background operations
         * @param requestTimeoutMillis Default timeout for wallet requests
         * @param cachedMetadata Optional cached wallet metadata to avoid initial fetch
         * @param cachedEncryption Optional cached encryption scheme preference
         */
        fun create(
            uri: String,
            sessionManager: NwcSessionManager,
            scope: CoroutineScope,
            requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS,
            cachedMetadata: WalletMetadata? = null,
            cachedEncryption: EncryptionScheme? = null
        ): NwcWallet {
            val credentials = NwcUri.parse(uri).toCredentials()
            return NwcWallet(
                uri = uri,
                credentials = credentials,
                sessionManager = sessionManager,
                scope = scope,
                requestTimeoutMillis = requestTimeoutMillis,
                cachedMetadata = cachedMetadata,
                cachedEncryption = cachedEncryption
            )
        }

        /**
         * Create a new NwcWallet from parsed credentials.
         */
        fun create(
            credentials: NwcCredentials,
            sessionManager: NwcSessionManager,
            scope: CoroutineScope,
            requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS,
            cachedMetadata: WalletMetadata? = null,
            cachedEncryption: EncryptionScheme? = null
        ): NwcWallet = NwcWallet(
            uri = credentials.toUriString(),
            credentials = credentials,
            sessionManager = sessionManager,
            scope = scope,
            requestTimeoutMillis = requestTimeoutMillis,
            cachedMetadata = cachedMetadata,
            cachedEncryption = cachedEncryption
        )
    }
}
