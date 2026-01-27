package io.github.nicolals.nwc

import io.github.nicolals.nostr.codec.kotlinx.KotlinxNostrJsonCodec
import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.message.Filter
import io.github.nicolals.nostr.core.nip.NipModule
import io.github.nicolals.nostr.core.nip.SimpleNipModuleContext
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.primitives.SecretKey
import io.github.nicolals.nostr.core.primitives.UnixTimeSeconds
import io.github.nicolals.nostr.core.runtime.ConnectionStatus
import io.github.nicolals.nostr.core.runtime.PublishStatus
import io.github.nicolals.nostr.core.runtime.SessionConfig
import io.github.nicolals.nostr.core.transport.RelayTransportFactory
import io.github.nicolals.nostr.core.transport.RelayUrl
import io.github.nicolals.nostr.crypto.NostrCrypto
import io.github.nicolals.nostr.crypto.NostrSigner
import io.github.nicolals.nostr.nip04.Nip04Module
import io.github.nicolals.nostr.nip44.Nip44Module
import io.github.nicolals.nostr.nip47.Nip47Module
import io.github.nicolals.nostr.nip47.NwcEncryptionSelector
import io.github.nicolals.nostr.nip47.event.NwcInfoEvent
import io.github.nicolals.nostr.nip47.event.NwcNotificationEvent
import io.github.nicolals.nostr.nip47.event.NwcRequestEvent
import io.github.nicolals.nostr.nip47.event.NwcResponseEvent
import io.github.nicolals.nostr.nip47.model.NwcBalanceResult
import io.github.nicolals.nostr.nip47.model.NwcEncryption
import io.github.nicolals.nostr.nip47.model.NwcError
import io.github.nicolals.nostr.nip47.model.NwcGetBalanceRequest
import io.github.nicolals.nostr.nip47.model.NwcGetInfoRequest
import io.github.nicolals.nostr.nip47.model.NwcGetInfoResult
import io.github.nicolals.nostr.nip47.model.NwcInvoiceRequestItem
import io.github.nicolals.nostr.nip47.model.NwcInvoiceResult
import io.github.nicolals.nostr.nip47.model.NwcKeysendRequestItem
import io.github.nicolals.nostr.nip47.model.NwcListTransactionsRequest
import io.github.nicolals.nostr.nip47.model.NwcListTransactionsResult
import io.github.nicolals.nostr.nip47.model.NwcLookupInvoiceRequest
import io.github.nicolals.nostr.nip47.model.NwcMakeInvoiceRequest
import io.github.nicolals.nostr.nip47.model.NwcMultiPayInvoiceRequest
import io.github.nicolals.nostr.nip47.model.NwcMultiPayKeysendRequest
import io.github.nicolals.nostr.nip47.model.NwcNotification
import io.github.nicolals.nostr.nip47.model.NwcPayInvoiceRequest
import io.github.nicolals.nostr.nip47.model.NwcPayKeysendRequest
import io.github.nicolals.nostr.nip47.model.NwcPayResult
import io.github.nicolals.nostr.nip47.model.NwcPaymentReceivedNotification
import io.github.nicolals.nostr.nip47.model.NwcPaymentSentNotification
import io.github.nicolals.nostr.nip47.model.NwcRequest
import io.github.nicolals.nostr.nip47.model.NwcResponse
import io.github.nicolals.nostr.nip47.model.NwcResult
import io.github.nicolals.nostr.nip47.model.NwcTlvRecord
import io.github.nicolals.nostr.nip47.model.NwcTransaction
import io.github.nicolals.nostr.runtime.coroutines.CoroutinesRelaySession
import io.github.nicolals.nostr.runtime.coroutines.CoroutinesSubscriptionHandle
import io.github.nicolals.nostr.transport.ktor.KtorRelayTransportFactory
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

/**
 * Nostr Wallet Connect (NWC) client for Kotlin Multiplatform.
 *
 * This client handles all communication with an NWC-enabled lightning wallet,
 * providing a simple, ergonomic API for common operations like:
 * - Checking balance
 * - Paying invoices
 * - Creating invoices
 * - Listing transactions
 *
 * ## Usage
 *
 * ```kotlin
 * // Parse connection URI from QR code or deep link
 * val uri = NwcConnectionUri.parse("nostr+walletconnect://...")
 *     ?: error("Invalid NWC URI")
 *
 * // Create the client
 * val client = NwcClient(uri, scope)
 *
 * // Connect and wait for ready
 * client.connect()
 * if (client.awaitReady(timeoutMs = 5000)) {
 *     // Get balance
 *     when (val result = client.getBalance()) {
 *         is NwcResult.Success -> println("Balance: ${result.value}")
 *         is NwcResult.Failure -> println("Error: ${result.error.message}")
 *     }
 * }
 *
 * // Clean up
 * client.close()
 * ```
 *
 * ## Jetpack Compose Integration
 *
 * The client exposes StateFlow properties that can be collected in Compose:
 *
 * ```kotlin
 * @Composable
 * fun WalletScreen(client: NwcClient) {
 *     val state by client.state.collectAsState()
 *     val notifications by client.notifications.collectAsState(initial = null)
 *
 *     when (state) {
 *         NwcClientState.Connecting -> LoadingIndicator()
 *         NwcClientState.Ready -> WalletContent()
 *         is NwcClientState.Failed -> ErrorView(state.error)
 *         NwcClientState.Closed -> Text("Disconnected")
 *     }
 * }
 * ```
 *
 * ## Lifecycle
 *
 * The client should be created once and reused. For Compose apps, consider:
 * - Creating in a ViewModel with `viewModelScope`
 * - Using Hilt/Koin for DI
 * - Creating a singleton for app-wide access
 *
 * Always call [close] when done to release resources.
 */
class NwcClient private constructor(
    private val connectionUri: NwcConnectionUri,
    private val scope: CoroutineScope,
    private val transportFactory: RelayTransportFactory,
    cachedWalletInfo: WalletInfo?,
) {
    // ========== Internal State ==========

    private val codec = KotlinxNostrJsonCodec()
    private val ctx = SimpleNipModuleContext(codec, NostrCrypto)

    private val signer = NostrSigner.fromSecretKey(
        SecretKey.fromBytes(connectionUri.secret)
    ).let { result ->
        when (result) {
            is CryptoResult.Ok -> result.value
            is CryptoResult.Err -> throw IllegalStateException("Failed to create signer: ${result.error}")
        }
    }

    private val encryptionSelector = NwcEncryptionSelector { _walletInfo.value.preferredEncryption }

    private val additionalModules: List<NipModule> = listOf(
        Nip04Module(ctx),
        Nip44Module(ctx),
        Nip47Module(ctx, encryptionSelector),
    )

    // Session for the first relay (we'll only use one for simplicity)
    private var session: CoroutinesRelaySession? = null
    private var subscriptionHandle: CoroutinesSubscriptionHandle? = null
    private var notificationHandle: CoroutinesSubscriptionHandle? = null
    private var eventCollectorJob: Job? = null
    private var notificationCollectorJob: Job? = null
    private var sessionLifecycleJob: Job? = null

    // Pending requests awaiting responses
    private val pendingRequests = mutableMapOf<String, CompletableDeferred<NwcResponse>>()
    private val pendingMultiRequests = mutableMapOf<String, MutableMap<String, CompletableDeferred<NwcResponse>>>()

    // ========== Public State ==========

    private val _state = MutableStateFlow<NwcClientState>(NwcClientState.Disconnected)

    /**
     * Current state of the client.
     */
    val state: StateFlow<NwcClientState> = _state.asStateFlow()

    private val _walletInfo = MutableStateFlow(cachedWalletInfo ?: WalletInfo.default())

    /**
     * Information about the connected wallet.
     * Updated when the info event is fetched.
     */
    val walletInfo: StateFlow<WalletInfo> = _walletInfo.asStateFlow()

    private val _notifications = MutableSharedFlow<WalletNotification>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Flow of wallet notifications (payment received, payment sent).
     */
    val notifications: SharedFlow<WalletNotification> = _notifications.asSharedFlow()

    /**
     * The connection URI used by this client.
     */
    val uri: NwcConnectionUri get() = connectionUri

    /**
     * Current encryption scheme used for communication.
     */
    val encryption: NwcEncryption get() = _walletInfo.value.preferredEncryption

    /**
     * Number of requests currently pending a response.
     *
     * Useful for UI to show activity indicator or for debugging.
     */
    val pendingRequestCount: Int get() = pendingRequests.size

    // ========== Connection Management ==========

    /**
     * Connects to the NWC relay and starts listening for responses.
     *
     * This will:
     * 1. Connect to the relay
     * 2. Fetch the wallet info event
     * 3. Subscribe to response events
     * 4. Optionally subscribe to notifications
     *
     * If the client is already connected or connecting, this call does nothing unless
     * forceReconnect is true.
     *
     * @param forceReconnect If true, forces a new connection even if already connected/connecting.
     * @param connectionTimeoutMs Timeout for the WebSocket connection establishment.
     */
    fun connect(forceReconnect: Boolean = false, connectionTimeoutMs: Long = DEFAULT_CONNECTION_TIMEOUT_MS) {
        val currentState = _state.value
        if (!forceReconnect && (currentState == NwcClientState.Ready || currentState == NwcClientState.Connecting)) {
            return
        }

        _state.value = NwcClientState.Connecting

        // Cancel any previous lifecycle observation
        sessionLifecycleJob?.cancel()
        sessionLifecycleJob = null

        val relayUrl = RelayUrl.parse(connectionUri.relays.first())
        // Configure session with fail-fast behavior and periodic pinging.
        // NWC events are ephemeral (not replayed by relay), so we don't use
        // auto-reconnection - it won't help recover in-flight requests.
        val sessionConfig = SessionConfig.Minimal.copy(
            pingIntervalMillis = PING_INTERVAL_MS,
        )

        val newSession = CoroutinesRelaySession(
            url = relayUrl,
            moduleContext = ctx,
            transportFactory = transportFactory,
            scope = scope,
            // Use Minimal config for fail-fast behavior:
            // - No automatic reconnection (app controls retry)
            // - No publish retry (fail immediately on disconnect)
            // - Periodic ping to detect dead connections faster
            config = sessionConfig,
            additionalModules = additionalModules,
        )

        session = newSession
        newSession.connect()

        // Launch initialization coroutine
        scope.launch {
            try {
                // Wait for connection with configurable timeout
                val connected = newSession.awaitConnected(timeoutMs = connectionTimeoutMs)
                if (!connected) {
                    _state.value = NwcClientState.Failed(
                        io.github.nicolals.nwc.NwcError.ConnectionError("Failed to connect to relay")
                    )
                    return@launch
                }

                // Fetch wallet info event
                fetchWalletInfo(newSession)

                // Subscribe to responses
                subscribeToResponses(newSession)

                // Subscribe to notifications if supported
                if (_walletInfo.value.supportsNotifications) {
                    subscribeToNotifications(newSession)
                }

                _state.value = NwcClientState.Ready

                // Start observing session lifecycle for disconnection
                observeSessionLifecycle(newSession)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = NwcClientState.Failed(
                    io.github.nicolals.nwc.NwcError.ConnectionError(e.message ?: "Unknown error", e)
                )
            }
        }
    }

    /**
     * Observes the session's connection status and handles disconnection.
     * When the session connection fails, transitions client to Disconnected
     * and fails all pending requests.
     *
     * Note: NWC events are ephemeral and won't be replayed by the relay,
     * so we fail pending requests immediately on disconnect rather than
     * waiting for reconnection.
     */
    private fun observeSessionLifecycle(observedSession: CoroutinesRelaySession) {
        sessionLifecycleJob = scope.launch {
            observedSession.connectionStatusFlow.collect { status ->
                // Only handle disconnection if this is still our active session
                // and we're currently in Ready state
                if (session !== observedSession) return@collect
                if (_state.value != NwcClientState.Ready) return@collect

                when (status) {
                    is ConnectionStatus.Failed, ConnectionStatus.Disconnected -> {
                        handleSessionDisconnected()
                    }
                    else -> { /* Connected, Connecting, Reconnecting - ignore */ }
                }
            }
        }
    }

    /**
     * Handles session disconnection - transitions to Disconnected and fails pending requests.
     */
    private fun handleSessionDisconnected() {
        // Only transition if we're currently Ready
        if (_state.value != NwcClientState.Ready) return

        // Transition to Disconnected (allows reconnection via connect())
        _state.value = NwcClientState.Disconnected

        // Clean up the dead session
        eventCollectorJob?.cancel()
        eventCollectorJob = null
        notificationCollectorJob?.cancel()
        notificationCollectorJob = null
        subscriptionHandle?.close()
        subscriptionHandle = null
        notificationHandle?.close()
        notificationHandle = null
        session?.close()
        session = null

        // Fail all pending requests with connection error.
        // NWC events are ephemeral - they won't be replayed after reconnection,
        // so we fail immediately. The app should use lookup_invoice to verify.
        val error = io.github.nicolals.nwc.NwcError.ConnectionError("Connection lost")
        pendingRequests.values.forEach {
            it.completeExceptionally(NwcException(error))
        }
        pendingRequests.clear()
        pendingMultiRequests.values.forEach { items ->
            items.values.forEach { it.completeExceptionally(NwcException(error)) }
        }
        pendingMultiRequests.clear()
    }

    /**
     * Suspends until the client is ready or timeout occurs.
     *
     * @param timeoutMs Maximum time to wait in milliseconds.
     * @return True if ready, false if timeout or connection failed.
     */
    suspend fun awaitReady(timeoutMs: Long = 10_000): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            _state.first { it == NwcClientState.Ready || it is NwcClientState.Failed }
        } == NwcClientState.Ready
    }

    /**
     * Closes the client and releases all resources.
     */
    fun close() {
        _state.value = NwcClientState.Closed
        sessionLifecycleJob?.cancel()
        sessionLifecycleJob = null
        eventCollectorJob?.cancel()
        eventCollectorJob = null
        notificationCollectorJob?.cancel()
        notificationCollectorJob = null
        subscriptionHandle?.close()
        subscriptionHandle = null
        notificationHandle?.close()
        notificationHandle = null
        session?.close()
        session = null

        // Fail all pending requests
        val error = io.github.nicolals.nwc.NwcError.Cancelled()
        pendingRequests.values.forEach {
            it.completeExceptionally(NwcException(error))
        }
        pendingRequests.clear()
        pendingMultiRequests.values.forEach { items ->
            items.values.forEach { it.completeExceptionally(NwcException(error)) }
        }
        pendingMultiRequests.clear()
    }

    // ========== Wallet Operations ==========

    /**
     * Gets the current wallet balance.
     */
    suspend fun getBalance(timeoutMs: Long = 30_000): io.github.nicolals.nwc.NwcResult<Amount> {
        return executeRequest(NwcGetBalanceRequest, timeoutMs).map { response ->
            when (val result = response.result) {
                is NwcBalanceResult -> Amount.fromMsats(result.balanceMsat)
                else -> throw IllegalStateException("Unexpected result type: ${response.resultType}")
            }
        }
    }

    /**
     * Gets detailed wallet information.
     */
    suspend fun getInfo(timeoutMs: Long = 30_000): io.github.nicolals.nwc.NwcResult<WalletDetails> {
        return executeRequest(NwcGetInfoRequest, timeoutMs).map { response ->
            when (val result = response.result) {
                is NwcGetInfoResult -> WalletDetails(
                    alias = result.alias,
                    color = result.color,
                    pubkey = result.pubkey,
                    network = result.network,
                    blockHeight = result.blockHeight,
                    blockHash = result.blockHash,
                    methods = result.methods?.mapNotNull { NwcCapability.fromValue(it) }?.toSet() ?: emptySet(),
                    notifications = result.notifications?.mapNotNull { NwcNotificationType.fromValue(it) }?.toSet() ?: emptySet(),
                )
                else -> throw IllegalStateException("Unexpected result type: ${response.resultType}")
            }
        }
    }

    /**
     * Pays a BOLT-11 invoice.
     *
     * @param invoice The BOLT-11 invoice string.
     * @param amount Optional amount for zero-amount invoices.
     * @param timeoutMs Timeout in milliseconds (default: 60 seconds).
     * @param verifyOnTimeout If true and the request times out, will attempt to verify
     *        the payment status via lookup_invoice before reporting failure.
     */
    suspend fun payInvoice(
        invoice: String,
        amount: Amount? = null,
        timeoutMs: Long = 60_000,
        verifyOnTimeout: Boolean = true,
    ): io.github.nicolals.nwc.NwcResult<PaymentResult> {
        val request = NwcPayInvoiceRequest(
            invoice = invoice,
            amountMsat = amount?.msats,
        )

        val result = executeRequest(request, timeoutMs)

        return when {
            result.isSuccess -> result.map { response ->
                when (val payResult = response.result) {
                    is NwcPayResult -> PaymentResult(
                        preimage = payResult.preimage,
                        feesPaid = payResult.feesPaidMsat?.let { Amount.fromMsats(it) },
                    )
                    else -> throw IllegalStateException("Unexpected result type: ${response.resultType}")
                }
            }
            result.isTimeout && verifyOnTimeout -> {
                // Payment timed out - verify actual status via lookup_invoice
                verifyPaymentOnTimeout(invoice)
            }
            else -> result.map { throw IllegalStateException("Unreachable") }
        }
    }

    /**
     * Verifies payment status after a timeout via lookup_invoice.
     *
     * This is called automatically when payInvoice times out and verifyOnTimeout is true.
     */
    private suspend fun verifyPaymentOnTimeout(invoice: String): io.github.nicolals.nwc.NwcResult<PaymentResult> {
        val verification = lookupInvoice(
            params = LookupInvoiceParams(invoice = invoice),
            timeoutMs = 10_000, // Shorter timeout for verification
        )

        return when {
            verification.isSuccess -> {
                val tx = verification.getOrThrow()
                when (tx.state) {
                    TransactionState.SETTLED -> {
                        // Payment actually succeeded!
                        io.github.nicolals.nwc.NwcResult.success(
                            PaymentResult(
                                preimage = tx.preimage ?: "",
                                feesPaid = tx.feesPaid,
                            )
                        )
                    }
                    TransactionState.PENDING -> {
                        // Still pending - return special status
                        io.github.nicolals.nwc.NwcResult.failure(
                            io.github.nicolals.nwc.NwcError.PaymentPending(
                                message = "Payment sent but not yet confirmed",
                                paymentHash = tx.paymentHash,
                            )
                        )
                    }
                    TransactionState.FAILED, TransactionState.EXPIRED, null -> {
                        // Payment failed, expired, or unknown state
                        io.github.nicolals.nwc.NwcResult.failure(
                            io.github.nicolals.nwc.NwcError.Timeout(
                                message = "Payment timed out and could not be verified"
                            )
                        )
                    }
                }
            }
            else -> {
                // Couldn't verify - return original timeout
                io.github.nicolals.nwc.NwcResult.failure(
                    io.github.nicolals.nwc.NwcError.Timeout(
                        message = "Payment timed out (verification also failed)"
                    )
                )
            }
        }
    }

    /**
     * Pays a BOLT-11 invoice with parameters.
     */
    suspend fun payInvoice(
        params: PayInvoiceParams,
        timeoutMs: Long = 60_000,
        verifyOnTimeout: Boolean = true,
    ): io.github.nicolals.nwc.NwcResult<PaymentResult> =
        payInvoice(params.invoice, params.amount, timeoutMs, verifyOnTimeout)

    /**
     * Sends a keysend payment.
     */
    suspend fun payKeysend(
        params: KeysendParams,
        timeoutMs: Long = 60_000,
    ): io.github.nicolals.nwc.NwcResult<PaymentResult> {
        val request = NwcPayKeysendRequest(
            pubkey = params.pubkey,
            amountMsat = params.amount.msats,
            preimage = params.preimage,
            tlvRecords = params.tlvRecords.map { NwcTlvRecord(it.type, it.value) }.takeIf { it.isNotEmpty() },
        )
        return executeRequest(request, timeoutMs).map { response ->
            when (val result = response.result) {
                is NwcPayResult -> PaymentResult(
                    preimage = result.preimage,
                    feesPaid = result.feesPaidMsat?.let { Amount.fromMsats(it) },
                )
                else -> throw IllegalStateException("Unexpected result type: ${response.resultType}")
            }
        }
    }

    /**
     * Creates a new invoice.
     */
    suspend fun makeInvoice(
        params: MakeInvoiceParams,
        timeoutMs: Long = 30_000,
    ): io.github.nicolals.nwc.NwcResult<Transaction> {
        val request = NwcMakeInvoiceRequest(
            amountMsat = params.amount.msats,
            description = params.description,
            descriptionHash = params.descriptionHash,
            expirySeconds = params.expirySeconds,
        )
        return executeRequest(request, timeoutMs).map { response ->
            when (val result = response.result) {
                is NwcInvoiceResult -> result.transaction.toTransaction()
                else -> throw IllegalStateException("Unexpected result type: ${response.resultType}")
            }
        }
    }

    /**
     * Looks up an invoice by payment hash or invoice string.
     */
    suspend fun lookupInvoice(
        params: LookupInvoiceParams,
        timeoutMs: Long = 30_000,
    ): io.github.nicolals.nwc.NwcResult<Transaction> {
        val request = NwcLookupInvoiceRequest(
            paymentHash = params.paymentHash,
            invoice = params.invoice,
        )
        return executeRequest(request, timeoutMs).map { response ->
            when (val result = response.result) {
                is NwcInvoiceResult -> result.transaction.toTransaction()
                else -> throw IllegalStateException("Unexpected result type: ${response.resultType}")
            }
        }
    }

    /**
     * Lists transactions matching the given filters.
     */
    suspend fun listTransactions(
        params: ListTransactionsParams = ListTransactionsParams(),
        timeoutMs: Long = 30_000,
    ): io.github.nicolals.nwc.NwcResult<List<Transaction>> {
        val request = NwcListTransactionsRequest(
            from = params.from,
            until = params.until,
            limit = params.limit?.toLong(),
            offset = params.offset?.toLong(),
            unpaid = params.unpaid,
            type = params.type?.value,
        )
        return executeRequest(request, timeoutMs).map { response ->
            when (val result = response.result) {
                is NwcListTransactionsResult -> result.transactions.map { it.toTransaction() }
                else -> throw IllegalStateException("Unexpected result type: ${response.resultType}")
            }
        }
    }

    /**
     * Pays multiple invoices in a batch.
     */
    suspend fun multiPayInvoice(
        items: List<MultiPayInvoiceItem>,
        timeoutMs: Long = 120_000,
    ): io.github.nicolals.nwc.NwcResult<Map<String, MultiPayItemResult>> {
        val request = NwcMultiPayInvoiceRequest(
            invoices = items.map { NwcInvoiceRequestItem(it.invoice, it.amount?.msats, null, it.id) }
        )
        return executeMultiRequest(request, items.map { it.id }, timeoutMs).map { responses ->
            responses.mapValues { (_, response) ->
                val responseError = response.error
                if (responseError != null) {
                    MultiPayItemResult.Failed(responseError.toNwcError())
                } else {
                    when (val result = response.result) {
                        is NwcPayResult -> MultiPayItemResult.Success(
                            PaymentResult(result.preimage, result.feesPaidMsat?.let { Amount.fromMsats(it) })
                        )
                        else -> MultiPayItemResult.Failed(
                            io.github.nicolals.nwc.NwcError.ProtocolError("Unexpected result type")
                        )
                    }
                }
            }
        }
    }

    /**
     * Sends multiple keysend payments in a batch.
     */
    suspend fun multiPayKeysend(
        items: List<MultiKeysendItem>,
        timeoutMs: Long = 120_000,
    ): io.github.nicolals.nwc.NwcResult<Map<String, MultiPayItemResult>> {
        val request = NwcMultiPayKeysendRequest(
            keysends = items.map {
                NwcKeysendRequestItem(
                    pubkey = it.pubkey,
                    amountMsat = it.amount.msats,
                    preimage = it.preimage,
                    tlvRecords = it.tlvRecords.map { tlv -> NwcTlvRecord(tlv.type, tlv.value) }.takeIf { list -> list.isNotEmpty() },
                    id = it.id,
                )
            }
        )
        return executeMultiRequest(request, items.map { it.id }, timeoutMs).map { responses ->
            responses.mapValues { (_, response) ->
                val responseError = response.error
                if (responseError != null) {
                    MultiPayItemResult.Failed(responseError.toNwcError())
                } else {
                    when (val result = response.result) {
                        is NwcPayResult -> MultiPayItemResult.Success(
                            PaymentResult(result.preimage, result.feesPaidMsat?.let { Amount.fromMsats(it) })
                        )
                        else -> MultiPayItemResult.Failed(
                            io.github.nicolals.nwc.NwcError.ProtocolError("Unexpected result type")
                        )
                    }
                }
            }
        }
    }

    /**
     * Checks the status of a previously initiated payment.
     *
     * Useful after app restart to resolve payments that were in-flight.
     * This looks up the invoice by payment hash to get its current state.
     *
     * @param paymentHash The payment hash to check.
     * @param timeoutMs Timeout for the lookup.
     * @return The transaction details including current state.
     */
    suspend fun checkPaymentStatus(
        paymentHash: String,
        timeoutMs: Long = 30_000,
    ): io.github.nicolals.nwc.NwcResult<Transaction> {
        return lookupInvoice(
            params = LookupInvoiceParams(paymentHash = paymentHash),
            timeoutMs = timeoutMs,
        )
    }

    /**
     * Refreshes the wallet info event from the relay.
     */
    suspend fun refreshWalletInfo(): io.github.nicolals.nwc.NwcResult<WalletInfo> {
        val currentSession = session ?: return io.github.nicolals.nwc.NwcResult.failure(
            io.github.nicolals.nwc.NwcError.ConnectionError("Not connected")
        )
        return try {
            fetchWalletInfo(currentSession)
            io.github.nicolals.nwc.NwcResult.success(_walletInfo.value)
        } catch (e: Exception) {
            io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.ConnectionError(e.message ?: "Failed to fetch wallet info", e)
            )
        }
    }

    // ========== Internal Methods ==========

    private suspend fun fetchWalletInfo(session: CoroutinesRelaySession) {
        val filter = Filter(
            kinds = listOf(NwcInfoEvent.KIND),
            authors = listOf(connectionUri.walletPubkey),
            limit = 1,
        )

        val handle = session.subscribe(listOf(filter))
        try {
            val event = withTimeoutOrNull(5_000L) {
                var result: Event? = null
                handle.events.first { event ->
                    result = event
                    true
                }
                result
            }

            if (event != null) {
                val infoEvent = NwcInfoEvent.fromEventOrNull(event)
                if (infoEvent != null) {
                    _walletInfo.value = WalletInfo.fromInfoEvent(infoEvent)
                }
            }
        } finally {
            handle.close()
        }
    }

    private fun subscribeToResponses(session: CoroutinesRelaySession) {
        val filter = Filter(
            kinds = listOf(NwcResponseEvent.KIND),
            authors = listOf(connectionUri.walletPubkey),
            tagFilters = mapOf("p" to listOf(connectionUri.clientPubkey.hex)),
        )

        val handle = session.subscribe(listOf(filter))
        subscriptionHandle = handle

        eventCollectorJob = scope.launch {
            handle.events.collect { event ->
                handleResponseEvent(event)
            }
        }
    }

    private fun subscribeToNotifications(session: CoroutinesRelaySession) {
        val nip44Kind = NwcNotificationEvent.KIND
        val nip04Kind = NwcNotificationEvent.LEGACY_KIND

        val filter = Filter(
            kinds = listOf(nip44Kind, nip04Kind),
            authors = listOf(connectionUri.walletPubkey),
            tagFilters = mapOf("p" to listOf(connectionUri.clientPubkey.hex)),
        )

        val handle = session.subscribe(listOf(filter))
        notificationHandle = handle

        notificationCollectorJob = scope.launch {
            handle.events.collect { event ->
                handleNotificationEvent(event)
            }
        }
    }

    private fun handleResponseEvent(event: Event) {
        val encryptionSelector = NwcEncryptionSelector { _walletInfo.value.preferredEncryption }
        val responseEvent = NwcResponseEvent.fromEventOrNull(event, encryptionSelector) ?: return

        // Decrypt the response
        val response = when (val result = responseEvent.decryptForClient(ctx, connectionUri.secret)) {
            is CryptoResult.Ok -> result.value
            is CryptoResult.Err -> {
                // Log decryption error but don't crash
                return
            }
        }

        // Find pending request by event ID
        val requestId = responseEvent.requestEventId?.hex
        val responseId = responseEvent.responseId

        if (requestId != null) {
            // Single request response
            pendingRequests.remove(requestId)?.complete(response)
        }

        if (responseId != null) {
            // Multi-request response - find by response ID (d tag)
            val multiItems = pendingMultiRequests.values.firstOrNull { it.containsKey(responseId) }
            multiItems?.remove(responseId)?.complete(response)
        }
    }

    private fun handleNotificationEvent(event: Event) {
        val notificationEvent = NwcNotificationEvent.fromEventOrNull(event) ?: return

        // Decrypt the notification
        val notification = when (val result = notificationEvent.decryptForClient(ctx, connectionUri.secret)) {
            is CryptoResult.Ok -> result.value
            is CryptoResult.Err -> return
        }

        val walletNotification = when (notification) {
            is NwcPaymentReceivedNotification -> WalletNotification.PaymentReceived(
                notification.transaction.toTransaction()
            )
            is NwcPaymentSentNotification -> WalletNotification.PaymentSent(
                notification.transaction.toTransaction()
            )
            else -> return
        }

        _notifications.tryEmit(walletNotification)
    }

    private suspend fun ensureConnected(timeoutMs: Long): Boolean {
        // If already ready, return immediately
        if (_state.value == NwcClientState.Ready) return true

        // Retry connection establishment with exponential backoff.
        // This handles transient failures (DNS hiccups, relay temporarily unreachable,
        // packet loss during handshake, iOS network stack issues after backgrounding, etc.)
        // without requiring auto-reconnection at the session level (which wouldn't help
        // recover in-flight NWC requests anyway since events are ephemeral).
        val timeMark = TimeSource.Monotonic.markNow()
        var attempt = 0

        while (true) {
            attempt++
            val elapsed = timeMark.elapsedNow().inWholeMilliseconds
            val remaining = timeoutMs - elapsed

            // Check if we've exhausted our timeout budget
            if (remaining <= 0) return false

            // If disconnected or failed, trigger connection with appropriate timeout
            val currentState = _state.value
            if (currentState == NwcClientState.Disconnected || currentState is NwcClientState.Failed) {
                // Use shorter per-attempt timeout to leave room for retries
                val perAttemptTimeout = minOf(remaining, CONNECTION_ATTEMPT_TIMEOUT_MS)
                connect(forceReconnect = true, connectionTimeoutMs = perAttemptTimeout)
            }

            // Wait for Ready state
            val waitTimeout = minOf(remaining, CONNECTION_ATTEMPT_TIMEOUT_MS + WALLET_INFO_TIMEOUT_MS)
            val ready = try {
                withTimeoutOrNull(waitTimeout) {
                    _state.first { it == NwcClientState.Ready || it is NwcClientState.Failed }
                } == NwcClientState.Ready
            } catch (e: Exception) {
                false
            }

            if (ready) return true

            // Connection failed - check if we should retry
            val newElapsed = timeMark.elapsedNow().inWholeMilliseconds
            val newRemaining = timeoutMs - newElapsed

            // Need minimum time for another attempt, and respect max attempts
            if (newRemaining < MIN_RETRY_BUDGET_MS || attempt >= MAX_CONNECTION_ATTEMPTS) {
                return false
            }

            // Exponential backoff: 500ms, 1000ms, 2000ms, capped at 3000ms
            val backoffMs = minOf(
                CONNECTION_BACKOFF_BASE_MS * (1L shl (attempt - 1)),
                CONNECTION_BACKOFF_MAX_MS
            )
            // Don't backoff longer than remaining time minus minimum for next attempt
            val actualBackoff = minOf(backoffMs, newRemaining - MIN_RETRY_BUDGET_MS)
            if (actualBackoff > 0) {
                delay(actualBackoff)
            }
        }
    }

    private suspend fun executeRequest(
        request: NwcRequest,
        timeoutMs: Long,
    ): io.github.nicolals.nwc.NwcResult<NwcResponse> {
        // Ensure we are connected before proceeding
        // Give generous time for connection establishment with retries.
        // For short timeouts, use up to half; for longer timeouts, cap at 15s to leave
        // enough time for the actual request/response cycle.
        val connectionTimeout = when {
            timeoutMs <= 10_000L -> timeoutMs / 2
            timeoutMs <= 30_000L -> minOf(15_000L, timeoutMs / 2)
            else -> 15_000L
        }
        if (!ensureConnected(connectionTimeout)) {
            val state = _state.value
            val message = if (state is NwcClientState.Failed) {
                state.error.message
            } else {
                "Failed to establish connection to relay"
            }
            return io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.ConnectionError(message)
            )
        }
        
        val currentSession = session ?: return io.github.nicolals.nwc.NwcResult.failure(
            io.github.nicolals.nwc.NwcError.ConnectionError("Not connected")
        )

        if (_state.value != NwcClientState.Ready) {
            return io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.ConnectionError("Client not ready")
            )
        }

        // Check session connection status - fail fast if already disconnected
        if (currentSession.connectionStatus != ConnectionStatus.Connected) {
            // Session is not connected (reconnecting, failed, etc.)
            // State will be updated by the lifecycle observer
            return io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.ConnectionError("Connection lost")
            )
        }

        // Create and sign the request event
        val encryption = _walletInfo.value.preferredEncryption
        val templateResult = NwcRequestEvent.template(
            walletPubkey = connectionUri.walletPubkey,
            request = request,
            createdAt = UnixTimeSeconds.now(),
            ctx = ctx,
            clientPrivateKey = connectionUri.secret,
            encryption = encryption,
        )

        val template = when (templateResult) {
            is CryptoResult.Ok -> templateResult.value
            is CryptoResult.Err -> return io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.CryptoError("Failed to encrypt request: ${templateResult.error}")
            )
        }

        val signedEvent = signer.sign(template)
        val requestId = signedEvent.event.id.hex

        // Register pending request before publishing
        val deferred = CompletableDeferred<NwcResponse>()
        pendingRequests[requestId] = deferred

        // Publish the event and track status
        val publishHandle = currentSession.publish(signedEvent.event)

        // Track if we ever saw Pending status (meaning bytes were sent to relay)
        // Check immediately - status might already be Pending or past it before we subscribe
        // Pending = bytes being sent, Accepted = relay confirmed receipt
        // Either means bytes went out (or at least an attempt was made)
        val initialStatus = publishHandle.status
        var sawPending = initialStatus == PublishStatus.Pending ||
            initialStatus is PublishStatus.Accepted

        // Wait for publish to be acknowledged by relay (with short timeout)
        // This tells us if the request made it to the relay
        val publishStatus = withTimeoutOrNull(PUBLISH_ACK_TIMEOUT_MS) {
            publishHandle.statusFlow.first { status ->
                if (status == PublishStatus.Pending) {
                    sawPending = true
                }
                status.isTerminal
            }
        }

        // Check publish result
        when (publishStatus) {
            is PublishStatus.Accepted -> {
                // Request is on the relay - wallet will receive it
                // Now wait for the wallet's response
            }

            is PublishStatus.Rejected -> {
                // Relay rejected our event - request NOT sent
                pendingRequests.remove(requestId)
                return io.github.nicolals.nwc.NwcResult.failure(
                    io.github.nicolals.nwc.NwcError.ConnectionError(
                        "Relay rejected request: ${publishStatus.message}"
                    )
                )
            }

            is PublishStatus.Abandoned, is PublishStatus.Replaced -> {
                // Connection failed or publish was replaced
                pendingRequests.remove(requestId)
                return if (sawPending) {
                    // We sent bytes but never got ack - UNKNOWN state
                    // Treat as timeout so app knows to verify
                    io.github.nicolals.nwc.NwcResult.failure(
                        io.github.nicolals.nwc.NwcError.Timeout(
                            message = "Connection lost after sending request",
                            durationMs = PUBLISH_ACK_TIMEOUT_MS
                        )
                    )
                } else {
                    // Never sent - safe to retry
                    io.github.nicolals.nwc.NwcResult.failure(
                        io.github.nicolals.nwc.NwcError.ConnectionError("Request not sent: connection failed")
                    )
                }
            }

            null -> {
                // Timeout waiting for publish ack
                // Check current status to determine what happened
                val currentStatus = publishHandle.status
                pendingRequests.remove(requestId)
                return if (currentStatus == PublishStatus.Pending || sawPending) {
                    // Bytes were sent but no relay ack yet - UNKNOWN state
                    io.github.nicolals.nwc.NwcResult.failure(
                        io.github.nicolals.nwc.NwcError.Timeout(
                            message = "Timeout waiting for relay acknowledgment",
                            durationMs = PUBLISH_ACK_TIMEOUT_MS
                        )
                    )
                } else {
                    // Still queued - request never sent
                    io.github.nicolals.nwc.NwcResult.failure(
                        io.github.nicolals.nwc.NwcError.ConnectionError("Request not sent: publish timeout")
                    )
                }
            }

            else -> {
                // Non-terminal status (shouldn't happen due to first{} filter)
                // Continue to wait for response
            }
        }

        // Request is on relay - now wait for wallet response
        return try {
            val response = withTimeoutOrNull(timeoutMs) {
                deferred.await()
            }

            if (response == null) {
                pendingRequests.remove(requestId)
                // Request was sent (Accepted) but no response - timeout
                io.github.nicolals.nwc.NwcResult.failure(
                    io.github.nicolals.nwc.NwcError.Timeout(
                        message = "Timeout waiting for wallet response",
                        durationMs = timeoutMs
                    )
                )
            } else {
                pendingRequests.remove(requestId)
                val responseError = response.error
                if (responseError != null) {
                    io.github.nicolals.nwc.NwcResult.failure(responseError.toNwcError())
                } else {
                    io.github.nicolals.nwc.NwcResult.success(response)
                }
            }
        } catch (e: CancellationException) {
            pendingRequests.remove(requestId)
            throw e
        } catch (e: NwcException) {
            // Connection lost while waiting for response.
            // Since we're past publish-Accepted, the request was sent.
            // Return Timeout (not ConnectionError) so the app knows to verify
            // before retrying - the payment might have succeeded.
            // NWC events are ephemeral and won't be replayed, so we can't
            // recover the response via subscription.
            pendingRequests.remove(requestId)
            io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.Timeout(
                    message = "Connection lost while waiting for response (request was sent)",
                    durationMs = timeoutMs
                )
            )
        } catch (e: Exception) {
            pendingRequests.remove(requestId)
            io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.ProtocolError(e.message ?: "Unknown error", e)
            )
        }
    }

    private suspend fun executeMultiRequest(
        request: NwcRequest,
        itemIds: List<String>,
        timeoutMs: Long,
    ): io.github.nicolals.nwc.NwcResult<Map<String, NwcResponse>> {
        // Ensure we are connected before proceeding
        // Give generous time for connection establishment with retries
        val connectionTimeout = when {
            timeoutMs <= 10_000L -> timeoutMs / 2
            timeoutMs <= 30_000L -> minOf(15_000L, timeoutMs / 2)
            else -> 15_000L
        }
        if (!ensureConnected(connectionTimeout)) {
            val state = _state.value
            val message = if (state is NwcClientState.Failed) {
                state.error.message
            } else {
                "Failed to establish connection to relay"
            }
            return io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.ConnectionError(message)
            )
        }

        val currentSession = session ?: return io.github.nicolals.nwc.NwcResult.failure(
            io.github.nicolals.nwc.NwcError.ConnectionError("Not connected")
        )

        if (_state.value != NwcClientState.Ready) {
            return io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.ConnectionError("Client not ready")
            )
        }

        // Check session connection status - fail fast if already disconnected
        if (currentSession.connectionStatus != ConnectionStatus.Connected) {
            // Session is not connected (reconnecting, failed, etc.)
            return io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.ConnectionError("Connection lost")
            )
        }

        // Create and sign the request event
        val encryption = _walletInfo.value.preferredEncryption
        val templateResult = NwcRequestEvent.template(
            walletPubkey = connectionUri.walletPubkey,
            request = request,
            createdAt = UnixTimeSeconds.now(),
            ctx = ctx,
            clientPrivateKey = connectionUri.secret,
            encryption = encryption,
        )

        val template = when (templateResult) {
            is CryptoResult.Ok -> templateResult.value
            is CryptoResult.Err -> return io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.CryptoError("Failed to encrypt request: ${templateResult.error}")
            )
        }

        val signedEvent = signer.sign(template)
        val requestId = signedEvent.event.id.hex

        // Register pending requests for each item
        val deferreds = mutableMapOf<String, CompletableDeferred<NwcResponse>>()
        itemIds.forEach { id ->
            deferreds[id] = CompletableDeferred()
        }
        pendingMultiRequests[requestId] = deferreds

        // Publish the event and track status
        val publishHandle = currentSession.publish(signedEvent.event)

        // Track if we ever saw Pending status (meaning bytes were sent to relay)
        var sawPending = false

        // Wait for publish to be acknowledged by relay
        val publishStatus = withTimeoutOrNull(PUBLISH_ACK_TIMEOUT_MS) {
            publishHandle.statusFlow.first { status ->
                if (status == PublishStatus.Pending) {
                    sawPending = true
                }
                status.isTerminal
            }
        }

        // Check publish result
        when (publishStatus) {
            is PublishStatus.Accepted -> {
                // Request is on the relay - proceed to wait for responses
            }

            is PublishStatus.Rejected -> {
                pendingMultiRequests.remove(requestId)
                return io.github.nicolals.nwc.NwcResult.failure(
                    io.github.nicolals.nwc.NwcError.ConnectionError(
                        "Relay rejected request: ${publishStatus.message}"
                    )
                )
            }

            is PublishStatus.Abandoned, is PublishStatus.Replaced -> {
                pendingMultiRequests.remove(requestId)
                return if (sawPending) {
                    io.github.nicolals.nwc.NwcResult.failure(
                        io.github.nicolals.nwc.NwcError.Timeout(
                            message = "Connection lost after sending request",
                            durationMs = PUBLISH_ACK_TIMEOUT_MS
                        )
                    )
                } else {
                    io.github.nicolals.nwc.NwcResult.failure(
                        io.github.nicolals.nwc.NwcError.ConnectionError("Request not sent: connection failed")
                    )
                }
            }

            null -> {
                val currentStatus = publishHandle.status
                pendingMultiRequests.remove(requestId)
                return if (currentStatus == PublishStatus.Pending || sawPending) {
                    io.github.nicolals.nwc.NwcResult.failure(
                        io.github.nicolals.nwc.NwcError.Timeout(
                            message = "Timeout waiting for relay acknowledgment",
                            durationMs = PUBLISH_ACK_TIMEOUT_MS
                        )
                    )
                } else {
                    io.github.nicolals.nwc.NwcResult.failure(
                        io.github.nicolals.nwc.NwcError.ConnectionError("Request not sent: publish timeout")
                    )
                }
            }

            else -> {
                // Non-terminal status - continue
            }
        }

        return try {
            // Wait for all responses
            val responses = withTimeoutOrNull(timeoutMs) {
                val results = mutableMapOf<String, NwcResponse>()
                for ((id, deferred) in deferreds) {
                    results[id] = deferred.await()
                }
                results
            }

            pendingMultiRequests.remove(requestId)

            if (responses == null) {
                io.github.nicolals.nwc.NwcResult.failure(
                    io.github.nicolals.nwc.NwcError.Timeout(
                        message = "Timeout waiting for wallet responses",
                        durationMs = timeoutMs
                    )
                )
            } else {
                io.github.nicolals.nwc.NwcResult.success(responses)
            }
        } catch (e: CancellationException) {
            pendingMultiRequests.remove(requestId)
            throw e
        } catch (e: NwcException) {
            // Connection lost while waiting for responses
            // Since we're past publish-Accepted, the request was sent
            pendingMultiRequests.remove(requestId)
            io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.Timeout(
                    message = "Connection lost while waiting for responses (request was sent)",
                    durationMs = timeoutMs
                )
            )
        } catch (e: Exception) {
            pendingMultiRequests.remove(requestId)
            io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.ProtocolError(e.message ?: "Unknown error", e)
            )
        }
    }

    // ========== Factory ==========

    companion object {
        /** Timeout for waiting for relay to acknowledge publish (OK message) */
        private const val PUBLISH_ACK_TIMEOUT_MS = 5_000L

        /** Default timeout for WebSocket connection establishment (DNS + TCP + TLS + WS handshake) */
        private const val DEFAULT_CONNECTION_TIMEOUT_MS = 10_000L

        /**
         * Timeout per connection attempt (shorter to allow retries).
         * 7s is generous for mobile networks where DNS and TLS can be slow.
         */
        private const val CONNECTION_ATTEMPT_TIMEOUT_MS = 7_000L

        /** Timeout for fetching wallet info after connection */
        private const val WALLET_INFO_TIMEOUT_MS = 5_000L

        /**
         * Maximum number of connection attempts before giving up.
         * 3 attempts with exponential backoff provides good balance between
         * resilience and responsiveness for payment apps.
         *
         * Timing with 15s budget:
         * - Attempt 1: immediate (up to 7s)
         * - Attempt 2: after 500ms backoff (up to 7s)
         * - Attempt 3: after 1000ms backoff (up to remaining time)
         */
        private const val MAX_CONNECTION_ATTEMPTS = 3

        /** Base delay for exponential backoff between connection attempts */
        private const val CONNECTION_BACKOFF_BASE_MS = 500L

        /** Maximum backoff delay between connection attempts */
        private const val CONNECTION_BACKOFF_MAX_MS = 3_000L

        /**
         * Minimum time budget required to attempt another connection retry.
         * Should be slightly less than CONNECTION_ATTEMPT_TIMEOUT_MS to allow
         * a meaningful attempt.
         */
        private const val MIN_RETRY_BUDGET_MS = 4_000L

        /** Interval for WebSocket ping to detect dead connections (10 seconds) */
        private const val PING_INTERVAL_MS = 10_000L

        /**
         * Creates a new NWC client from a connection URI.
         *
         * @param uri The NWC connection URI (or the string to parse).
         * @param scope CoroutineScope for the client's operations.
         * @param httpClient Optional HttpClient for the Ktor transport.
         * @param cachedWalletInfo Optional cached wallet info to use before fetching from relay.
         */
        operator fun invoke(
            uri: NwcConnectionUri,
            scope: CoroutineScope,
            httpClient: HttpClient? = null,
            cachedWalletInfo: WalletInfo? = null,
        ): NwcClient {
            val transportFactory = KtorRelayTransportFactory(httpClient ?: HttpClient())
            val client = NwcClient(uri, scope, transportFactory, cachedWalletInfo)
            // Automatically start connecting when the client is created.
            // This allows connection setup to run in parallel with UI/app logic.
            // Operations will wait for connection if needed via ensureConnected().
            client.connect()
            return client
        }

        /**
         * Creates a new NWC client from a connection URI string.
         *
         * @param uriString The NWC connection URI string.
         * @param scope CoroutineScope for the client's operations.
         * @param httpClient Optional HttpClient for the Ktor transport.
         * @param cachedWalletInfo Optional cached wallet info to use before fetching from relay.
         * @return The NWC client, or null if the URI is invalid.
         */
        fun fromUri(
            uriString: String,
            scope: CoroutineScope,
            httpClient: HttpClient? = null,
            cachedWalletInfo: WalletInfo? = null,
        ): NwcClient? {
            val uri = NwcConnectionUri.parse(uriString) ?: return null
            return invoke(uri, scope, httpClient, cachedWalletInfo)
        }

        /**
         * Creates a new NWC client from a deep link URI.
         *
         * Handles both direct NWC URIs and callback URIs from wallet apps.
         *
         * @param deepLinkUri The deep link URI.
         * @param scope CoroutineScope for the client's operations.
         * @param httpClient Optional HttpClient for the Ktor transport.
         * @param cachedWalletInfo Optional cached wallet info to use before fetching from relay.
         * @return The NWC client, or null if the URI is invalid.
         */
        fun fromDeepLink(
            deepLinkUri: String,
            scope: CoroutineScope,
            httpClient: HttpClient? = null,
            cachedWalletInfo: WalletInfo? = null,
        ): NwcClient? {
            val uri = NwcConnectionUri.parseDeepLink(deepLinkUri) ?: return null
            return invoke(uri, scope, httpClient, cachedWalletInfo)
        }

        /**
         * Discovers wallet capabilities without maintaining a long-running connection.
         *
         * This is useful for wallet setup flows where you need to:
         * 1. Validate the URI
         * 2. Fetch wallet capabilities
         * 3. Show user what they're connecting to
         * 4. Store the connection if they approve
         *
         * The client is automatically closed after discovery completes.
         *
         * @param uri The NWC connection URI string.
         * @param httpClient Optional HttpClient for the Ktor transport.
         * @param timeoutMs Timeout for the entire discovery operation.
         * @return Discovery result with wallet info and details, or failure.
         */
        suspend fun discover(
            uri: String,
            httpClient: HttpClient? = null,
            timeoutMs: Long = 10_000,
        ): io.github.nicolals.nwc.NwcResult<WalletDiscovery> {
            val parsed = NwcConnectionUri.parse(uri)
                ?: return io.github.nicolals.nwc.NwcResult.failure(
                    io.github.nicolals.nwc.NwcError.ProtocolError("Invalid NWC URI")
                )

            val scope = CoroutineScope(SupervisorJob())
            val client = invoke(parsed, scope, httpClient)

            return try {
                client.connect()

                if (!client.awaitReady(timeoutMs)) {
                    return io.github.nicolals.nwc.NwcResult.failure(
                        io.github.nicolals.nwc.NwcError.ConnectionError("Failed to connect to relay")
                    )
                }

                val walletInfo = client.walletInfo.value
                val details = client.getInfo(timeoutMs / 2).getOrNull()

                io.github.nicolals.nwc.NwcResult.success(
                    WalletDiscovery(
                        uri = parsed,
                        walletInfo = walletInfo,
                        details = details,
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                io.github.nicolals.nwc.NwcResult.failure(
                    io.github.nicolals.nwc.NwcError.ConnectionError(e.message ?: "Discovery failed", e)
                )
            } finally {
                client.close()
                scope.cancel()
            }
        }

        /**
         * Discovers wallet capabilities from a deep link URI.
         *
         * @see discover
         */
        suspend fun discoverFromDeepLink(
            deepLinkUri: String,
            httpClient: HttpClient? = null,
            timeoutMs: Long = 10_000,
        ): io.github.nicolals.nwc.NwcResult<WalletDiscovery> {
            val nwcUri = NwcConnectionUri.parseDeepLink(deepLinkUri)?.raw
                ?: return io.github.nicolals.nwc.NwcResult.failure(
                    io.github.nicolals.nwc.NwcError.ProtocolError("Invalid deep link URI")
                )
            return discover(nwcUri, httpClient, timeoutMs)
        }
    }
}

/**
 * Result of wallet discovery containing all information needed to display
 * to the user and persist the connection.
 */
data class WalletDiscovery(
    /**
     * The parsed connection URI.
     */
    val uri: NwcConnectionUri,

    /**
     * Wallet info from the info event (capabilities, notifications, encryption).
     */
    val walletInfo: WalletInfo,

    /**
     * Detailed wallet info from get_info (alias, network, color, etc.).
     * May be null if get_info is not supported or failed.
     */
    val details: WalletDetails?,
) {
    /** Human-readable name for the wallet/node. */
    val alias: String? get() = details?.alias

    /** Bitcoin network (mainnet, testnet, etc.). */
    val network: String? get() = details?.network

    /** Node color (hex string). */
    val color: String? get() = details?.color

    /** Set of capabilities supported by the wallet. */
    val capabilities: Set<NwcCapability> get() = walletInfo.capabilities

    /** Whether the wallet supports pay_invoice. */
    val supportsPayInvoice: Boolean get() = NwcCapability.PAY_INVOICE in capabilities

    /** Whether the wallet supports make_invoice. */
    val supportsMakeInvoice: Boolean get() = NwcCapability.MAKE_INVOICE in capabilities

    /** Whether the wallet supports get_balance. */
    val supportsGetBalance: Boolean get() = NwcCapability.GET_BALANCE in capabilities

    /** Whether the wallet supports list_transactions. */
    val supportsListTransactions: Boolean get() = NwcCapability.LIST_TRANSACTIONS in capabilities

    /** Preferred encryption scheme. */
    val preferredEncryption: io.github.nicolals.nostr.nip47.model.NwcEncryption
        get() = walletInfo.preferredEncryption

    /** Whether encryption defaulted to NIP-04 (wallet didn't advertise encryption). */
    val encryptionDefaultedToNip04: Boolean get() = walletInfo.encryptionDefaultedToNip04
}

/**
 * State of the NWC client.
 */
sealed class NwcClientState {
    /** Client is disconnected. */
    data object Disconnected : NwcClientState()

    /** Client is connecting to the relay. */
    data object Connecting : NwcClientState()

    /** Client is connected and ready for operations. */
    data object Ready : NwcClientState()

    /** Client has been closed. */
    data object Closed : NwcClientState()

    /** Connection failed with an error. */
    data class Failed(val error: io.github.nicolals.nwc.NwcError) : NwcClientState()
}

// ========== Extension Functions ==========

private fun NwcTransaction.toTransaction(): Transaction = Transaction(
    type = type?.let { TransactionType.fromValue(it) } ?: TransactionType.INCOMING,
    state = state?.let { TransactionState.fromValue(it) },
    paymentHash = paymentHash ?: "",
    amount = Amount.fromMsats(amountMsat ?: 0),
    invoice = invoice,
    description = description,
    descriptionHash = descriptionHash,
    preimage = preimage,
    feesPaid = feesPaidMsat?.let { Amount.fromMsats(it) },
    createdAt = createdAt ?: 0,
    expiresAt = expiresAt,
    settledAt = settledAt,
)

private fun NwcError.toNwcError(): io.github.nicolals.nwc.NwcError =
    io.github.nicolals.nwc.NwcError.WalletError(NwcErrorCode.fromCode(code), message)
