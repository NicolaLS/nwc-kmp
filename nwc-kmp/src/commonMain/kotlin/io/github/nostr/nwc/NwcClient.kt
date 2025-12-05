@file:OptIn(ExperimentalUnsignedTypes::class)

package io.github.nostr.nwc

import io.github.nostr.nwc.internal.MethodNames
import io.github.nostr.nwc.internal.NotificationTypes
import io.github.nostr.nwc.internal.TAG_D
import io.github.nostr.nwc.internal.TAG_E
import io.github.nostr.nwc.internal.TAG_ENCRYPTION
import io.github.nostr.nwc.internal.TAG_EXPIRATION
import io.github.nostr.nwc.internal.TAG_P
import io.github.nostr.nwc.internal.asString
import io.github.nostr.nwc.internal.decodeRawResponse
import io.github.nostr.nwc.internal.defaultNwcHttpClient
import io.github.nostr.nwc.internal.parseNwcError
import io.github.nostr.nwc.internal.parseTransaction
import io.github.nostr.nwc.internal.parseWalletMetadata
import io.github.nostr.nwc.internal.tagValue
import io.github.nostr.nwc.internal.tagValues
import io.github.nostr.nwc.logging.NwcLog
import io.github.nostr.nwc.model.BalanceResult
import io.github.nostr.nwc.model.BitcoinAmount
import io.github.nostr.nwc.model.EncryptionScheme
import io.github.nostr.nwc.model.GetInfoResult
import io.github.nostr.nwc.model.KeysendParams
import io.github.nostr.nwc.model.KeysendResult
import io.github.nostr.nwc.model.KeysendTlvRecord
import io.github.nostr.nwc.model.ListTransactionsParams
import io.github.nostr.nwc.model.LookupInvoiceParams
import io.github.nostr.nwc.model.MakeInvoiceParams
import io.github.nostr.nwc.model.MultiKeysendItem
import io.github.nostr.nwc.model.MultiPayInvoiceItem
import io.github.nostr.nwc.model.MultiResult
import io.github.nostr.nwc.model.Network
import io.github.nostr.nwc.model.NwcError
import io.github.nostr.nwc.model.NwcCapability
import io.github.nostr.nwc.model.NwcNotificationType
import io.github.nostr.nwc.model.NwcRequestState
import io.github.nostr.nwc.model.NwcResult
import io.github.nostr.nwc.model.NwcWalletDescriptor
import io.github.nostr.nwc.model.PayInvoiceParams
import io.github.nostr.nwc.model.PayInvoiceResult
import io.github.nostr.nwc.model.Transaction
import io.github.nostr.nwc.model.TransactionState
import io.github.nostr.nwc.model.TransactionType
import io.github.nostr.nwc.model.WalletMetadata
import io.github.nostr.nwc.model.RawResponse
import io.github.nostr.nwc.model.WalletNotification
import io.ktor.client.HttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import nostr.runtime.coroutines.EagerRetryConfig
import nostr.runtime.coroutines.RequestResult
import nostr.runtime.coroutines.getOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlin.ExperimentalUnsignedTypes
import nostr.core.identity.Identity
import nostr.core.model.Event
import nostr.core.model.Filter
import nostr.core.model.PublishResult
import nostr.core.model.SubscriptionId
import nostr.core.session.RelaySessionOutput
import nostr.core.session.RelaySessionSettings
import nostr.crypto.Identity as SecpIdentity
import calculateConversationKey as nip44CalculateConversationKey
import decrypt as nip44Decrypt
import ensureSodium as nip44EnsureSodium
import encrypt as nip44Encrypt
import decryptWithSharedSecret as nip04DecryptWithSharedSecret
import deriveSharedSecret as nip04DeriveSharedSecret
import encryptWithSharedSecret as nip04EncryptWithSharedSecret
import kotlin.random.Random
import kotlin.time.TimeSource
import nostr.core.utils.toHexLower
import io.github.nostr.nwc.internal.NwcSessionRuntime
import io.github.nostr.nwc.internal.PendingRequestManager
import io.github.nostr.nwc.internal.PendingRequestManager.PendingRequest
import io.github.nostr.nwc.internal.json
import io.github.nostr.nwc.internal.jsonArrayOrNull
import io.github.nostr.nwc.internal.jsonObjectOrNull
import io.github.nostr.nwc.internal.longValueOrNull
import io.github.nostr.nwc.internal.string
import io.github.nostr.nwc.internal.parseEncryptionTagValues
import io.github.nostr.nwc.internal.selectPreferredEncryption

private const val SUBSCRIPTION_RESPONSES = "nwc-responses"
private const val SUBSCRIPTION_NOTIFICATIONS = "nwc-notifications"
private const val INFO_EVENT_KIND = 13194
private const val REQUEST_KIND = 23194
private const val RESPONSE_KIND = 23195
private const val NOTIFICATION_KIND = 23197
const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L
private const val NOTIFICATION_BUFFER_CAPACITY = 64

/**
 * Retry config for NWC foreground operations.
 * - staleTimeoutThreshold=1: Single timeout while connected triggers reconnect (aggressive stale detection)
 * - maxRetries=1: One retry after stale detection (we race relays, so per-relay retries are limited)
 * - writeTimeoutMillis=null: Disabled because write confirmation timeout doesn't mean write failed -
 *   the request may have been sent but confirmation was slow. This was causing false "network error"
 *   returns even when the payment actually went through.
 * - checkNetworkBeforeRequest=false: Disabled due to unreliable Android NetworkInterface API causing
 *   false "offline" errors even when network is working.
 *
 * TODO: Create upstream issue in nostr-kmp to improve foreground reliability:
 *       1. checkNetworkBeforeRequest should skip check when already connected
 *       2. writeTimeoutMillis should differentiate "write definitely failed" vs "confirmation timed out"
 *       3. Consider using Android ConnectivityManager instead of NetworkInterface (requires Context)
 */
private val NwcRetryConfig = EagerRetryConfig(
    maxRetries = 1,
    staleTimeoutThreshold = 1,
    writeTimeoutMillis = null,
    checkNetworkBeforeRequest = false
)

/** Timeout for subscription setup per relay during initialization */
private const val SUBSCRIPTION_SETUP_TIMEOUT_MS = 5_000L

class NwcClient private constructor(
    private val credentials: NwcCredentials,
    private val scope: CoroutineScope,
    private val requestTimeoutMillis: Long,
    private val session: NwcSession,
    private val ownsSession: Boolean,
    private val httpClient: HttpClient,
    private val ownsHttpClient: Boolean,
    private val interceptors: List<NwcClientInterceptor>,
    private val initialMetadata: WalletMetadata?,
    private val initialEncryption: EncryptionScheme?
) : NwcClientContract {

    private val hasInterceptors = interceptors.isNotEmpty()
    private val logTag = "NwcClient"

    /**
     * Initialization state machine for background initialization.
     * Tracks progress of session setup and subscription creation.
     */
    private sealed class InitState {
        /** Initial state before initialization starts */
        data object NotStarted : InitState()

        /** Initialization is in progress */
        data object Initializing : InitState()

        /** All relays are ready */
        data class Ready(val readyRelays: Set<String>) : InitState()

        /** Some relays are ready, others are pending recovery */
        data class PartialReady(
            val readyRelays: Set<String>,
            val pendingRelays: Set<String>
        ) : InitState()

        /** Initialization failed completely */
        data class Failed(val error: Throwable) : InitState()
    }

    private val initState = MutableStateFlow<InitState>(InitState.NotStarted)
    private var initJob: Job? = null
    private var recoveryJob: Job? = null

    companion object {
        /**
         * Creates an NwcClient from a NWC URI string.
         * Returns immediately - initialization happens in background.
         *
         * @param uri NWC connection URI string (nostr+walletconnect://...)
         * @param scope CoroutineScope for background operations
         * @param httpClient Optional HTTP client for relay connections (created internally if null)
         * @param sessionSettings Relay session configuration
         * @param requestTimeoutMillis Default timeout for wallet requests
         * @param cachedMetadata Cached wallet metadata to avoid initial fetch
         * @param cachedEncryption Cached encryption scheme preference
         * @param interceptors Request/response observers
         */
        fun create(
            uri: String,
            scope: CoroutineScope,
            httpClient: HttpClient? = null,
            sessionSettings: RelaySessionSettings = RelaySessionSettings(),
            requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS,
            cachedMetadata: WalletMetadata? = null,
            cachedEncryption: EncryptionScheme? = null,
            interceptors: List<NwcClientInterceptor> = emptyList()
        ): NwcClient = create(
            NwcUri.parse(uri).toCredentials(),
            scope, httpClient, sessionSettings, requestTimeoutMillis,
            cachedMetadata, cachedEncryption, interceptors
        )

        /**
         * Creates an NwcClient from a parsed NwcUri.
         * Returns immediately - initialization happens in background.
         */
        fun create(
            uri: NwcUri,
            scope: CoroutineScope,
            httpClient: HttpClient? = null,
            sessionSettings: RelaySessionSettings = RelaySessionSettings(),
            requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS,
            cachedMetadata: WalletMetadata? = null,
            cachedEncryption: EncryptionScheme? = null,
            interceptors: List<NwcClientInterceptor> = emptyList()
        ): NwcClient = create(
            uri.toCredentials(),
            scope, httpClient, sessionSettings, requestTimeoutMillis,
            cachedMetadata, cachedEncryption, interceptors
        )

        /**
         * Creates an NwcClient from NwcCredentials.
         * Returns immediately - initialization happens in background.
         *
         * This is the primary factory method. It creates the HTTP client and session
         * internally if not provided, and starts background initialization.
         */
        fun create(
            credentials: NwcCredentials,
            scope: CoroutineScope,
            httpClient: HttpClient? = null,
            sessionSettings: RelaySessionSettings = RelaySessionSettings(),
            requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS,
            cachedMetadata: WalletMetadata? = null,
            cachedEncryption: EncryptionScheme? = null,
            interceptors: List<NwcClientInterceptor> = emptyList()
        ): NwcClient {
            val client = httpClient ?: defaultNwcHttpClient()
            val session = NwcSession.create(
                credentials = credentials,
                scope = scope,
                httpClient = client,
                sessionSettings = sessionSettings
            )
            return NwcClient(
                credentials = credentials,
                scope = scope,
                requestTimeoutMillis = requestTimeoutMillis,
                session = session,
                ownsSession = true,
                httpClient = client,
                ownsHttpClient = false, // Never own shared client; user-provided clients are their responsibility
                interceptors = interceptors,
                initialMetadata = cachedMetadata,
                initialEncryption = cachedEncryption
            ).also { it.startBackgroundInit() }
        }

        /**
         * Creates an NwcClient that reuses an existing session.
         * The caller retains ownership of the session and is responsible for closing it.
         *
         * @param session An existing NwcSession to reuse
         * @param requestTimeoutMillis Default timeout for wallet requests
         * @param cachedMetadata Cached wallet metadata to avoid initial fetch
         * @param cachedEncryption Cached encryption scheme preference
         * @param interceptors Request/response observers
         */
        fun createWithSession(
            session: NwcSession,
            requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS,
            cachedMetadata: WalletMetadata? = null,
            cachedEncryption: EncryptionScheme? = null,
            interceptors: List<NwcClientInterceptor> = emptyList()
        ): NwcClient = NwcClient(
            credentials = session.credentials,
            scope = session.coroutineScope,
            requestTimeoutMillis = requestTimeoutMillis,
            session = session,
            ownsSession = false,
            httpClient = session.httpClientInternal,
            ownsHttpClient = false,
            interceptors = interceptors,
            initialMetadata = cachedMetadata,
            initialEncryption = cachedEncryption
        ).also { it.startBackgroundInit() }
    }

    private val identity: Identity = SecpIdentity.fromPrivateKey(credentials.secretKey)
    private val clientPublicKeyHex: String = identity.publicKey.toString()
    private val walletPublicKeyHex: String = credentials.walletPublicKey.toString()
    private val nip44ConversationKey: UByteArray
    private val nip04SharedSecret: ByteArray
    private val supportedEncryptionOrder = listOf(
        EncryptionScheme.Nip44V2,
        EncryptionScheme.Nip04
    )
    private var activeEncryption: EncryptionScheme = determineInitialEncryption()

    private fun determineInitialEncryption(): EncryptionScheme {
        val explicit = initialEncryption?.takeUnless { it is EncryptionScheme.Unknown }
        if (explicit != null) return explicit
        val preferred = initialMetadata?.let { preferredEncryptionOrNull(it) }
        // NIP-47: if no encryption tag is advertised, default to NIP-04.
        return preferred ?: EncryptionScheme.Nip04
    }

    private fun activeEncryptionOrDefault(): EncryptionScheme =
        if (activeEncryption !is EncryptionScheme.Unknown) {
            activeEncryption
        } else if (canFallbackToNip04()) {
            EncryptionScheme.Nip04
        } else {
            EncryptionScheme.Nip44V2
        }

    private val pendingRequestManager = PendingRequestManager(logTag)

    private val _notifications = MutableSharedFlow<WalletNotification>(
        replay = 0,
        extraBufferCapacity = NOTIFICATION_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val notifications: SharedFlow<WalletNotification> = _notifications.asSharedFlow()

    private val walletMetadataState = MutableStateFlow<WalletMetadata?>(initialMetadata)
    override val walletMetadata: StateFlow<WalletMetadata?> = walletMetadataState.asStateFlow()

    private val notificationFilters = listOf(
        // Strict: wallet-authored notifications addressed to this client
        Filter(
            kinds = setOf(NOTIFICATION_KIND),
            authors = setOf(walletPublicKeyHex),
            tags = mapOf("#$TAG_P" to setOf(clientPublicKeyHex))
        ),
        // Permissive: all wallet-authored notifications (for wallets that omit p tag)
        // Safe because we only receive events from our wallet pubkey.
        Filter(
            kinds = setOf(NOTIFICATION_KIND),
            authors = setOf(walletPublicKeyHex)
        )
    )

    init {
        nip44EnsureSodium()
        nip44ConversationKey = nip44CalculateConversationKey(
            credentials.secretKey.toByteArray(),
            credentials.walletPublicKey.toByteArray()
        )
        nip04SharedSecret = nip04DeriveSharedSecret(
            credentials.secretKey.toByteArray(),
            credentials.walletPublicKey.toByteArray()
        )
    }

    override suspend fun close() {
        pendingRequestManager.cancelAll()
        if (ownsSession) {
            session.close()
        }
        if (ownsHttpClient) {
            runCatching { httpClient.close() }
        }
    }

    override suspend fun refreshWalletMetadata(timeoutMillis: Long): NwcResult<WalletMetadata> =
        runNwcCatching { refreshWalletMetadataInternal(timeoutMillis) }

    private suspend fun refreshWalletMetadataInternal(timeoutMillis: Long): WalletMetadata {
        // Wait for initialization to complete before accessing handles
        awaitReady(timeoutMillis)
        val handles = session.runtimeHandles
        val metadata = coroutineScope {
            val result = CompletableDeferred<WalletMetadata?>()
            val jobs = handles.map { handle ->
                async {
                    val value = fetchMetadataFrom(handle, timeoutMillis)
                    if (value != null) result.complete(value)
                    value
                }
            }
            launch {
                jobs.joinAll()
                result.complete(null)
            }
            val winner = result.await()
            if (winner != null) jobs.forEach { it.cancel() }
            winner
        }
        if (metadata != null) {
            walletMetadataState.value = metadata
            updateActiveEncryption(metadata)
            return metadata
        }
        throw NwcException("Unable to fetch wallet metadata from configured relays.")
    }

    // ==================== Generic Request Helpers ====================

    /**
     * Generic single-request executor that handles the common pattern:
     * build params → send request → parse JSON object result.
     */
    private suspend inline fun <R> execute(
        method: String,
        timeoutMillis: Long,
        buildParams: JsonObjectBuilder.() -> Unit = {},
        parseResult: (JsonObject) -> R
    ): R {
        val params = buildJsonObject(buildParams)
        val response = sendSingleRequest(method, params, timeoutMillis)
        val obj = response.result as? JsonObject
            ?: throw NwcProtocolException("$method response missing result object")
        return parseResult(obj)
    }

    /**
     * Generic multi-request executor that handles the common pattern:
     * send multi-request → parse each response → return map of results.
     */
    private suspend inline fun <R> executeMultiRequest(
        method: String,
        params: JsonObject,
        expectedKeys: Set<String>,
        timeoutMillis: Long,
        parseResult: (JsonObject) -> R
    ): Map<String, MultiResult<R>> {
        val responses = sendMultiRequest(method, params, expectedKeys, timeoutMillis)
        return responses.mapValues { (key, raw) ->
            raw.error?.let { MultiResult.Failure(it) } ?: run {
                val obj = raw.result as? JsonObject
                    ?: return@mapValues MultiResult.Failure(
                        NwcError("INVALID_RESULT", "Missing result for $method entry $key")
                    )
                runCatching { MultiResult.Success(parseResult(obj)) }.getOrElse { e ->
                    MultiResult.Failure(NwcError("PARSE_ERROR", e.message ?: "Failed to parse $method result"))
                }
            }
        }
    }

    // ==================== Flow-based Request Helpers ====================

    /**
     * Maximum timeout for Flow-based requests that wait indefinitely.
     * 10 minutes should be sufficient for even slow Lightning payments.
     */
    private val MAX_REQUEST_WAIT_MS = 600_000L

    /**
     * Generic helper that creates an [NwcRequest] for observing request state.
     *
     * Unlike [execute], this returns immediately with a controllable request handle.
     * The request waits for a response indefinitely (up to [MAX_REQUEST_WAIT_MS])
     * until cancelled or completed.
     *
     * @param method The NWC method name
     * @param buildParams Builder for request parameters
     * @param parseResult Parser for the response JSON
     * @return [NwcRequest] wrapping a [StateFlow] of [NwcRequestState]
     */
    private fun <R> executeAsRequest(
        method: String,
        buildParams: JsonObjectBuilder.() -> Unit = {},
        parseResult: (JsonObject) -> R
    ): NwcRequest<R> {
        val stateFlow = MutableStateFlow<NwcRequestState<R>>(NwcRequestState.Loading)

        val job = scope.launch {
            try {
                if (hasInterceptors) {
                    val params = buildJsonObject(buildParams)
                    interceptors.forEach { it.onRequest(method, params) }
                }
                val params = buildJsonObject(buildParams)
                val response = sendSingleRequest(method, params, MAX_REQUEST_WAIT_MS)
                val obj = response.result as? JsonObject
                    ?: throw NwcProtocolException("$method response missing result object")
                val result = parseResult(obj)
                stateFlow.value = NwcRequestState.Success(result)
            } catch (e: Throwable) {
                stateFlow.value = NwcRequestState.Failure(e.toFailure())
            }
        }

        // Get request ID - we build event just to get its ID for tracking
        // This is a bit wasteful but keeps the API simple
        val requestId = runCatching {
            buildRequestEvent(method, buildJsonObject(buildParams), null).id
        }.getOrDefault("unknown")

        return NwcRequest(
            state = stateFlow,
            requestId = requestId,
            job = job
        )
    }

    // ==================== API Methods ====================

    override suspend fun getBalance(timeoutMillis: Long): NwcResult<BalanceResult> = runNwcCatching {
        execute(MethodNames.GET_BALANCE, timeoutMillis) { obj ->
            val balanceMsats = obj["balance"]?.jsonPrimitive?.longValueOrNull()
                ?: throw NwcProtocolException("get_balance response missing balance")
            BalanceResult(BitcoinAmount.fromMsats(balanceMsats))
        }
    }

    override suspend fun getInfo(timeoutMillis: Long): NwcResult<GetInfoResult> = runNwcCatching {
        execute(MethodNames.GET_INFO, timeoutMillis) { obj ->
            val rawPubkey = obj.string("pubkey")
            val pubkey = if (rawPubkey.isNullOrBlank()) {
                NwcLog.warn(logTag) {
                    "get_info response missing pubkey, defaulting to wallet credential pubkey ${credentials.walletPublicKeyHex}"
                }
                credentials.walletPublicKeyHex
            } else {
                rawPubkey
            }
            GetInfoResult(
                alias = obj.string("alias"),
                color = obj.string("color"),
                pubkey = pubkey,
                network = Network.fromWire(obj.string("network")),
                blockHeight = obj["block_height"]?.jsonPrimitive?.longValueOrNull(),
                blockHash = obj.string("block_hash"),
                methods = obj.jsonArrayOrNull("methods")
                    ?.mapNotNull { NwcCapability.fromWire(it.asString()) }?.toSet() ?: emptySet(),
                notifications = obj.jsonArrayOrNull("notifications")
                    ?.mapNotNull { NwcNotificationType.fromWire(it.asString()) }?.toSet() ?: emptySet()
            )
        }
    }

    override suspend fun payInvoice(
        params: PayInvoiceParams,
        timeoutMillis: Long
    ): NwcResult<PayInvoiceResult> = runNwcCatching {
        execute(
            MethodNames.PAY_INVOICE,
            timeoutMillis,
            buildParams = {
                put("invoice", params.invoice)
                params.amount?.let { put("amount", it.msats) }
                params.metadata?.let { put("metadata", it) }
            }
        ) { obj -> parsePaymentResult(obj, "pay_invoice") }
    }

    override suspend fun multiPayInvoice(
        invoices: List<MultiPayInvoiceItem>,
        timeoutMillis: Long
    ): NwcResult<Map<String, MultiResult<PayInvoiceResult>>> = runNwcCatching {
        require(invoices.isNotEmpty()) { "multiPayInvoice requires at least one invoice" }
        val normalized = invoices.map { item -> (item.id ?: randomId()) to item }
        val expectedIds = normalized.map { it.first }.toSet()
        val params = buildJsonObject {
            put("invoices", buildJsonArray {
                for ((id, invoice) in normalized) {
                    add(buildJsonObject {
                        put("id", id)
                        put("invoice", invoice.invoice)
                        invoice.amount?.let { put("amount", it.msats) }
                        invoice.metadata?.let { put("metadata", it) }
                    })
                }
            })
        }
        executeMultiRequest(MethodNames.MULTI_PAY_INVOICE, params, expectedIds, timeoutMillis) { obj ->
            parsePaymentResult(obj, "multi_pay_invoice")
        }
    }

    override suspend fun payKeysend(
        params: KeysendParams,
        timeoutMillis: Long
    ): NwcResult<KeysendResult> = runNwcCatching {
        execute(
            MethodNames.PAY_KEYSEND,
            timeoutMillis,
            buildParams = {
                put("pubkey", params.destinationPubkey)
                put("amount", params.amount.msats)
                params.preimage?.let { put("preimage", it) }
                if (params.tlvRecords.isNotEmpty()) {
                    put("tlv_records", buildJsonArray {
                        params.tlvRecords.forEach { record ->
                            add(buildJsonObject {
                                put("type", record.type)
                                put("value", record.valueHex)
                            })
                        }
                    })
                }
            }
        ) { obj -> parsePaymentResult(obj, "pay_keysend") }
    }

    override suspend fun multiPayKeysend(
        items: List<MultiKeysendItem>,
        timeoutMillis: Long
    ): NwcResult<Map<String, MultiResult<KeysendResult>>> = runNwcCatching {
        require(items.isNotEmpty()) { "multiPayKeysend requires at least one payment" }
        val normalized = items.map { item -> (item.id ?: randomId()) to item }
        val expectedIds = normalized.map { it.first }.toSet()
        val params = buildJsonObject {
            put("keysends", buildJsonArray {
                for ((id, payment) in normalized) {
                    add(buildJsonObject {
                        put("id", id)
                        put("pubkey", payment.destinationPubkey)
                        put("amount", payment.amount.msats)
                        payment.preimage?.let { put("preimage", it) }
                        if (payment.tlvRecords.isNotEmpty()) {
                            put("tlv_records", buildJsonArray {
                                payment.tlvRecords.forEach { record ->
                                    add(buildJsonObject {
                                        put("type", record.type)
                                        put("value", record.valueHex)
                                    })
                                }
                            })
                        }
                    })
                }
            })
        }
        executeMultiRequest(MethodNames.MULTI_PAY_KEYSEND, params, expectedIds, timeoutMillis) { obj ->
            parsePaymentResult(obj, "multi_pay_keysend")
        }
    }

    override suspend fun makeInvoice(
        params: MakeInvoiceParams,
        timeoutMillis: Long
    ): NwcResult<Transaction> = runNwcCatching {
        execute(
            MethodNames.MAKE_INVOICE,
            timeoutMillis,
            buildParams = {
                put("amount", params.amount.msats)
                params.description?.let { put("description", it) }
                params.descriptionHash?.let { put("description_hash", it) }
                params.expirySeconds?.let { put("expiry", it) }
                params.metadata?.let { put("metadata", it) }
            }
        ) { obj -> parseTransaction(obj) }
    }

    override suspend fun lookupInvoice(
        params: LookupInvoiceParams,
        timeoutMillis: Long
    ): NwcResult<Transaction> = runNwcCatching {
        execute(
            MethodNames.LOOKUP_INVOICE,
            timeoutMillis,
            buildParams = {
                params.paymentHash?.let { put("payment_hash", it) }
                params.invoice?.let { put("invoice", it) }
            }
        ) { obj -> parseTransaction(obj) }
    }

    override suspend fun listTransactions(
        params: ListTransactionsParams,
        timeoutMillis: Long
    ): NwcResult<List<Transaction>> = runNwcCatching {
        execute(
            MethodNames.LIST_TRANSACTIONS,
            timeoutMillis,
            buildParams = {
                params.fromTimestamp?.let { put("from", it) }
                params.untilTimestamp?.let { put("until", it) }
                params.limit?.let { put("limit", it) }
                params.offset?.let { put("offset", it) }
                if (params.includeUnpaidInvoices) put("unpaid", true)
                params.type?.let { put("type", it.toWire()) }
            }
        ) { obj ->
            val array = obj.jsonArrayOrNull("transactions") ?: JsonArray(emptyList())
            array.map { element ->
                val txnObj = element as? JsonObject
                    ?: throw NwcProtocolException("list_transactions entry must be object")
                parseTransaction(txnObj)
            }
        }
    }

    override suspend fun describeWallet(timeoutMillis: Long): NwcResult<NwcWalletDescriptor> = runNwcCatching {
        val metadata = refreshWalletMetadata(timeoutMillis).getOrThrow()
        val info = getInfo(timeoutMillis).getOrThrow()
        NwcWalletDescriptor(
            uri = credentials.toUri(),
            metadata = metadata,
            info = info,
            negotiatedEncryption = activeEncryption,
            relays = credentials.relays,
            lud16 = credentials.lud16
        )
    }

    // ==================== Flow-based API Methods ====================
    //
    // These methods return [NwcRequest] wrappers that allow consumers to:
    // - Observe request state via StateFlow (Loading → Success/Failure)
    // - Control how long to wait (no library-enforced timeout)
    // - Cancel and cleanup resources when done
    //
    // Use these for operations where the wallet may be slow to respond (e.g., payments).

    /**
     * Initiates a payment and returns an observable request handle.
     *
     * Unlike [payInvoice], this returns immediately and does not enforce a timeout.
     * Consumers can observe [NwcRequest.state] to track progress and decide
     * how long to wait.
     *
     * Example:
     * ```kotlin
     * val request = client.payInvoiceRequest(params)
     *
     * // Wait up to 30 seconds, then show "pending" UI
     * val result = request.awaitResult(30.seconds)
     * when (result) {
     *     is NwcRequestState.Success -> showSuccess(result.value)
     *     is NwcRequestState.Failure -> showError(result.failure)
     *     null, NwcRequestState.Loading -> {
     *         showPendingUI() // Still waiting, but show user it's pending
     *         // Continue observing if desired:
     *         request.state.collect { ... }
     *     }
     * }
     *
     * // When done (e.g., user navigates away):
     * request.cancel()
     * ```
     *
     * @param params Payment parameters (invoice, optional amount override, metadata)
     * @return [NwcRequest] for observing payment progress
     */
    override fun payInvoiceRequest(params: PayInvoiceParams): NwcRequest<PayInvoiceResult> =
        executeAsRequest(
            MethodNames.PAY_INVOICE,
            buildParams = {
                put("invoice", params.invoice)
                params.amount?.let { put("amount", it.msats) }
                params.metadata?.let { put("metadata", it) }
            }
        ) { obj -> parsePaymentResult(obj, "pay_invoice") }

    /**
     * Initiates a keysend payment and returns an observable request handle.
     *
     * @param params Keysend parameters (destination pubkey, amount, optional TLV records)
     * @return [NwcRequest] for observing payment progress
     * @see payInvoiceRequest for usage example
     */
    override fun payKeysendRequest(params: KeysendParams): NwcRequest<KeysendResult> =
        executeAsRequest(
            MethodNames.PAY_KEYSEND,
            buildParams = {
                put("pubkey", params.destinationPubkey)
                put("amount", params.amount.msats)
                params.preimage?.let { put("preimage", it) }
                if (params.tlvRecords.isNotEmpty()) {
                    put("tlv_records", buildJsonArray {
                        params.tlvRecords.forEach { record ->
                            add(buildJsonObject {
                                put("type", record.type)
                                put("value", record.valueHex)
                            })
                        }
                    })
                }
            }
        ) { obj -> parsePaymentResult(obj, "pay_keysend") }

    /**
     * Fetches wallet balance and returns an observable request handle.
     *
     * @return [NwcRequest] for observing the balance request
     */
    override fun getBalanceRequest(): NwcRequest<BalanceResult> =
        executeAsRequest(MethodNames.GET_BALANCE) { obj ->
            val balanceMsats = obj["balance"]?.jsonPrimitive?.longValueOrNull()
                ?: throw NwcProtocolException("get_balance response missing balance")
            BalanceResult(BitcoinAmount.fromMsats(balanceMsats))
        }

    /**
     * Fetches wallet info and returns an observable request handle.
     *
     * @return [NwcRequest] for observing the info request
     */
    override fun getInfoRequest(): NwcRequest<GetInfoResult> =
        executeAsRequest(MethodNames.GET_INFO) { obj ->
            val rawPubkey = obj.string("pubkey")
            val pubkey = if (rawPubkey.isNullOrBlank()) {
                credentials.walletPublicKeyHex
            } else {
                rawPubkey
            }
            GetInfoResult(
                alias = obj.string("alias"),
                color = obj.string("color"),
                pubkey = pubkey,
                network = Network.fromWire(obj.string("network")),
                blockHeight = obj["block_height"]?.jsonPrimitive?.longValueOrNull(),
                blockHash = obj.string("block_hash"),
                methods = obj.jsonArrayOrNull("methods")
                    ?.mapNotNull { NwcCapability.fromWire(it.asString()) }?.toSet() ?: emptySet(),
                notifications = obj.jsonArrayOrNull("notifications")
                    ?.mapNotNull { NwcNotificationType.fromWire(it.asString()) }?.toSet() ?: emptySet()
            )
        }

    /**
     * Creates an invoice and returns an observable request handle.
     *
     * @param params Invoice parameters (amount, description, expiry)
     * @return [NwcRequest] for observing invoice creation
     */
    override fun makeInvoiceRequest(params: MakeInvoiceParams): NwcRequest<Transaction> =
        executeAsRequest(
            MethodNames.MAKE_INVOICE,
            buildParams = {
                put("amount", params.amount.msats)
                params.description?.let { put("description", it) }
                params.descriptionHash?.let { put("description_hash", it) }
                params.expirySeconds?.let { put("expiry", it) }
            }
        ) { obj -> parseTransaction(obj) }

    /**
     * Looks up an invoice and returns an observable request handle.
     *
     * @param params Lookup parameters (payment hash or bolt11 invoice)
     * @return [NwcRequest] for observing the lookup
     */
    override fun lookupInvoiceRequest(params: LookupInvoiceParams): NwcRequest<Transaction> =
        executeAsRequest(
            MethodNames.LOOKUP_INVOICE,
            buildParams = {
                params.paymentHash?.let { put("payment_hash", it) }
                params.invoice?.let { put("invoice", it) }
            }
        ) { obj -> parseTransaction(obj) }

    /**
     * Lists transactions and returns an observable request handle.
     *
     * @param params Filter/pagination parameters
     * @return [NwcRequest] for observing the transaction list
     */
    override fun listTransactionsRequest(params: ListTransactionsParams): NwcRequest<List<Transaction>> =
        executeAsRequest(
            MethodNames.LIST_TRANSACTIONS,
            buildParams = {
                params.fromTimestamp?.let { put("from", it) }
                params.untilTimestamp?.let { put("until", it) }
                params.limit?.let { put("limit", it) }
                params.offset?.let { put("offset", it) }
                if (params.includeUnpaidInvoices) put("unpaid", true)
                params.type?.let { put("type", it.toWire()) }
            }
        ) { obj ->
            val array = obj.jsonArrayOrNull("transactions") ?: JsonArray(emptyList())
            array.map { element ->
                val txnObj = element as? JsonObject
                    ?: throw NwcProtocolException("list_transactions entry must be object")
                parseTransaction(txnObj)
            }
        }

    // ==================== Background Initialization ====================

    /**
     * Starts background initialization. Called from create() factory method.
     * Returns immediately - work happens in background coroutine.
     */
    private fun startBackgroundInit() {
        require(credentials.relays.isNotEmpty()) { "No relays provided in credentials" }

        initState.value = InitState.Initializing
        NwcLog.info(logTag) {
            "Starting background initialization for ${credentials.walletPublicKeyHex} " +
                "across ${credentials.relays.size} relay(s)"
        }

        initJob = scope.launch {
            try {
                // Step 1: Open session (connect to relays)
                session.open(handleOutput = ::handleOutput) { relaySession, relay ->
                    NwcLog.debug(logTag) { "Subscribing to notification stream on relay $relay" }
                    relaySession.subscribe(SUBSCRIPTION_NOTIFICATIONS, notificationFilters)
                }

                // Step 2: Create subscriptions for each relay
                val responseFilter = Filter(
                    kinds = setOf(RESPONSE_KIND),
                    authors = setOf(walletPublicKeyHex),
                    tags = mapOf("#$TAG_P" to setOf(clientPublicKeyHex))
                )

                val startedRelays = session.runtimeHandles.map { it.url }.toSet()
                val missingRelays = credentials.relays.toSet() - startedRelays
                val results = session.runtimeHandles.map { handle ->
                    val subscription = session.sessionRuntime().createResponseSubscription(
                        relay = handle.url,
                        filters = listOf(responseFilter),
                        timeoutMillis = SUBSCRIPTION_SETUP_TIMEOUT_MS,
                        checkNetwork = false  // Disabled: unreliable Android NetworkInterface API
                    )
                    handle.url to (subscription != null)
                }

                val ready = results.filter { it.second }.map { it.first }.toSet()
                val failed = (results.filter { !it.second }.map { it.first }.toSet()) + missingRelays

                when {
                    ready.isNotEmpty() && failed.isEmpty() -> {
                        initState.value = InitState.Ready(ready)
                        NwcLog.info(logTag) {
                            "Initialization complete - all ${ready.size} relay(s) ready"
                        }
                    }
                    ready.isNotEmpty() -> {
                        initState.value = InitState.PartialReady(ready, failed)
                        NwcLog.info(logTag) {
                            "Initialization partial - ${ready.size} relay(s) ready, " +
                                "${failed.size} pending recovery"
                        }
                        // Start background recovery for failed relays
                        launchRecoveryTask(failed, responseFilter)
                    }
                    else -> {
                        initState.value = InitState.Failed(
                            NwcNetworkException(
                                "Failed to establish response subscriptions on any relay - " +
                                    "all ${credentials.relays.size} relay(s) timed out or failed"
                            )
                        )
                        NwcLog.error(logTag) {
                            "Initialization failed - no relays available"
                        }
                        // Try recovering all relays in the background to take advantage of idle time.
                        launchRecoveryTask(credentials.relays.toSet(), responseFilter)
                    }
                }

            } catch (e: Throwable) {
                NwcLog.error(logTag, e) { "Background initialization failed" }
                initState.value = InitState.Failed(e)
            }
        }
    }

    /**
     * Waits for initialization to be ready enough for requests.
     * Called at the start of each request method.
     *
     * If init previously failed due to network unavailability, retries initialization
     * since network may now be available.
     *
     * @param timeoutMillis Maximum time to wait for initialization
     * @return List of session handles that are ready for requests
     * @throws NwcNetworkException if initialization failed
     * @throws NwcTimeoutException if initialization didn't complete in time
     */
    private suspend fun awaitReady(timeoutMillis: Long): List<NwcSessionRuntime.SessionHandle> {
        val current = initState.value
        if (current is InitState.Failed) {
            NwcLog.info(logTag) { "Init previously failed; retrying initialization before serving request" }
            initState.value = InitState.NotStarted
            startBackgroundInit()
        } else if (current is InitState.NotStarted) {
            startBackgroundInit()
        }

        val state = withTimeoutOrNull(timeoutMillis) {
            initState.first { it is InitState.Ready || it is InitState.PartialReady || it is InitState.Failed }
        } ?: throw NwcTimeoutException("Timed out waiting for client initialization after ${timeoutMillis}ms")

        return when (state) {
            is InitState.Ready -> session.runtimeHandles.filter {
                state.readyRelays.contains(it.url) && it.responseSubscription != null
            }
            is InitState.PartialReady -> session.runtimeHandles.filter {
                state.readyRelays.contains(it.url) && it.responseSubscription != null
            }
            is InitState.Failed -> throw NwcNetworkException(
                "Client initialization failed: ${state.error.message}",
                state.error
            )
            else -> throw NwcNetworkException("Unexpected init state: $state", null)
        }
    }

    /**
     * Launches a background task to recover failed relays.
     * Best-effort - doesn't block or fail the client.
     */
    private fun launchRecoveryTask(failedRelays: Set<String>, responseFilter: Filter) {
        if (failedRelays.isEmpty()) return
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            var pending = failedRelays.toMutableSet()
            NwcLog.debug(logTag) { "Starting recovery task for ${pending.size} relay(s)" }

            while (isActive && pending.isNotEmpty()) {
                val recovered = mutableSetOf<String>()
                for (relay in pending) {
                    val handle = session.runtimeHandles.find { it.url == relay } ?: run {
                        val started = session.sessionRuntime().ensureRelay(
                            relay = relay,
                            handleOutput = ::handleOutput,
                            configure = { relaySession, url ->
                                relaySession.subscribe(SUBSCRIPTION_NOTIFICATIONS, notificationFilters)
                            }
                        )
                        if (!started) {
                            NwcLog.debug(logTag) { "Recovery could not start relay $relay" }
                            continue
                        }
                        session.runtimeHandles.find { it.url == relay }
                    } ?: continue

                    val subscription = session.sessionRuntime().createResponseSubscription(
                        relay = relay,
                        filters = listOf(responseFilter),
                        timeoutMillis = SUBSCRIPTION_SETUP_TIMEOUT_MS,
                        checkNetwork = false  // Disabled: unreliable Android NetworkInterface API
                    )

                    if (subscription != null) {
                        recovered += relay
                    } else {
                        NwcLog.debug(logTag) { "Recovery attempt failed for relay $relay" }
                    }
                }

                if (recovered.isNotEmpty()) {
                    val currentState = initState.value
                    val readySoFar = when (currentState) {
                        is InitState.PartialReady -> currentState.readyRelays
                        is InitState.Ready -> currentState.readyRelays
                        else -> emptySet()
                    }
                    val pendingSoFar = when (currentState) {
                        is InitState.PartialReady -> currentState.pendingRelays
                        is InitState.Failed -> pending
                        else -> pending
                    }
                    val newReady = readySoFar + recovered
                    val newPending = (pendingSoFar - recovered)
                    initState.value = if (newPending.isEmpty()) {
                        InitState.Ready(newReady)
                    } else {
                        InitState.PartialReady(newReady, newPending)
                    }
                    NwcLog.info(logTag) { "Recovered ${recovered.size} relay(s): ${recovered.joinToString()}" }
                    pending.removeAll(recovered)
                }

                if (pending.isNotEmpty()) {
                    delay(3_000L)
                }
            }
        }
    }

    private suspend fun fetchMetadataFrom(
        handle: NwcSessionRuntime.SessionHandle,
        timeoutMillis: Long
    ): WalletMetadata? {
        val filter = Filter(
            kinds = setOf(INFO_EVENT_KIND),
            authors = setOf(walletPublicKeyHex),
            limit = 1
        )
        NwcLog.debug(logTag) { "Querying wallet metadata from relay ${handle.url}" }

        // Use SmartRelaySession.query for automatic connection handling and retry
        val result = handle.session.query(
            filters = listOf(filter),
            timeoutMillis = timeoutMillis,
            retryConfig = NwcRetryConfig
        )

        return when (result) {
            is RequestResult.Success -> {
                val event = result.value.firstOrNull()
                if (event != null) {
                    NwcLog.debug(logTag) { "Received wallet metadata event ${event.id} from relay ${handle.url}" }
                    parseWalletMetadata(event)
                } else {
                    NwcLog.debug(logTag) { "No wallet metadata event found on relay ${handle.url}" }
                    null
                }
            }
            is RequestResult.Timeout -> {
                NwcLog.warn(logTag) { "Timed out waiting for wallet metadata on relay ${handle.url}" }
                null
            }
            is RequestResult.ConnectionFailed -> {
                NwcLog.warn(logTag) { "Connection failed fetching metadata from relay ${handle.url}: ${result.lastError}" }
                null
            }
        }
    }

    private fun updateActiveEncryption(metadata: WalletMetadata): EncryptionScheme {
        val selected = selectPreferredEncryption(metadata, supportedEncryptionOrder)
        activeEncryption = selected
        return selected
    }

    private fun preferredEncryptionOrNull(metadata: WalletMetadata): EncryptionScheme? =
        runCatching { selectPreferredEncryption(metadata, supportedEncryptionOrder) }.getOrNull()

    private suspend fun sendSingleRequest(
        method: String,
        params: JsonObject,
        timeoutMillis: Long,
        expirationSeconds: Long? = null
    ): RawResponse {
        if (hasInterceptors) {
            interceptors.forEach { it.onRequest(method, params) }
        }
        val event = buildRequestEvent(method, params, expirationSeconds)

        // Register for auth retry tracking (uses a dummy deferred since we use SharedSubscription)
        val dummyDeferred = CompletableDeferred<RawResponse>()
        pendingRequestManager.register(event.id, PendingRequest.Single(method, dummyDeferred))

        try {
            NwcLog.debug(logTag) { "Publishing $method request as event ${event.id}" }
            val result = awaitResponseViaSharedSubscription(event, timeoutMillis)
            pendingRequestManager.remove(event.id, dummyDeferred)

            val responseEvent = when (result) {
                is RequestResult.Success -> result.value
                is RequestResult.Timeout -> {
                    throw NwcTimeoutException("Timed out waiting for $method response")
                }
                is RequestResult.ConnectionFailed -> {
                    throw NwcNetworkException(
                        "Connection failed while waiting for $method response: ${result.lastError ?: "unknown"}"
                    )
                }
            }

            NwcLog.debug(logTag) { "Received response for $method request event ${event.id}" }
            val response = decodeResponse(responseEvent)
            response.error?.let { throw NwcRequestException(it) }
            if (hasInterceptors) {
                interceptors.forEach { it.onResponse(method, response) }
            }
            return response
        } catch (failure: Throwable) {
            pendingRequestManager.remove(event.id, dummyDeferred)
            if (failure is NwcException) throw failure
            NwcLog.error(logTag, failure) { "Failed to execute $method request event ${event.id}" }
            throw NwcException("Failed to execute request $method", failure)
        }
    }

    /**
     * Wait for a response using requestOneVia across all relays.
     * Races all relays - first successful response wins.
     *
     * Uses SmartRelaySession.requestOneVia which provides:
     * - Race-free expectAndPublish (registers expectation before publishing)
     * - Auto-connect if needed
     * - Stale connection detection and reconnection
     * - Retry logic with EagerRetryConfig
     *
     * @return RequestResult with the response event, or failure details (Timeout/ConnectionFailed)
     * @throws NwcNetworkException if no response subscriptions are available (relay connection failed)
     */
    private suspend fun awaitResponseViaSharedSubscription(
        requestEvent: Event,
        timeoutMillis: Long
    ): RequestResult<Event> {
        // Wait for initialization to complete (bounded by request timeout)
        val handlesWithSubscription = awaitReady(timeoutMillis)
        if (handlesWithSubscription.isEmpty()) {
            throw NwcNetworkException(
                "No response subscriptions available - initialization failed"
            )
        }

        // Use NwcRetryConfig for stale connection detection:
        // - If timeout occurs while "connected", triggers reconnect and retry
        // - Detects dead connections (e.g., WiFi disabled) without waiting for ping
        val retryConfig = NwcRetryConfig

        // Launch requestOneVia on all relays and race them
        val requests = handlesWithSubscription.map { handle ->
            scope.async {
                handle.session.requestOneVia(
                    subscription = handle.responseSubscription!!,
                    requestEvent = requestEvent,
                    correlationId = requestEvent.id,
                    timeoutMillis = timeoutMillis,
                    retryConfig = retryConfig
                )
            }
        }

        // Collect all results - return first success, or aggregate failures
        val failures = mutableListOf<RequestResult<Event>>()

        return coroutineScope {
            val resultChannel = Channel<RequestResult<Event>>(requests.size)

            requests.forEach { deferred ->
                launch {
                    val result = deferred.await()
                    resultChannel.send(result)
                }
            }

            repeat(requests.size) {
                val result = resultChannel.receive()
                when (result) {
                    is RequestResult.Success -> {
                        // Cancel remaining requests and return success
                        requests.forEach { it.cancel() }
                        return@coroutineScope result
                    }
                    else -> failures.add(result)
                }
            }

            // All relays failed - prefer Timeout over ConnectionFailed
            // When user sets a timeout, they care about whether it timed out, not transient connection issues
            failures.firstOrNull { it is RequestResult.Timeout }
                ?: failures.firstOrNull()
                ?: RequestResult.Timeout(timeoutMillis)
        }
    }

    private suspend fun sendMultiRequest(
        method: String,
        params: JsonObject,
        expectedKeys: Set<String>,
        timeoutMillis: Long,
        expirationSeconds: Long? = null
    ): Map<String, RawResponse> {
        require(expectedKeys.isNotEmpty()) { "Multi request expected keys cannot be empty" }
        awaitReady(timeoutMillis)
        if (hasInterceptors) {
            interceptors.forEach { it.onRequest(method, params) }
        }
        val event = buildRequestEvent(method, params, expirationSeconds)
        val deferred = CompletableDeferred<Map<String, RawResponse>>()
        pendingRequestManager.register(
            event.id,
            PendingRequest.Multi(
                method = method,
                expectedKeys = expectedKeys,
                results = mutableMapOf(),
                deferred = deferred
            )
        )
        val firstResponseResult = try {
            NwcLog.debug(logTag) { "Publishing multi request $method (event ${event.id}) expecting ${expectedKeys.size} keys" }
            awaitResponseViaSharedSubscription(event, timeoutMillis)
        } catch (failure: Throwable) {
            pendingRequestManager.remove(event.id, deferred)
            throw failure
        }
        when (firstResponseResult) {
            is RequestResult.Success -> {
                // Process the first response to seed the multi map; additional responses will be handled via subscriptions.
                processResponseEvent(firstResponseResult.value, requestIdOverride = event.id)
            }
            is RequestResult.Timeout -> {
                pendingRequestManager.remove(event.id, deferred)
                throw NwcTimeoutException("Timed out waiting for $method response")
            }
            is RequestResult.ConnectionFailed -> {
                pendingRequestManager.remove(event.id, deferred)
                throw NwcNetworkException(
                    "Connection failed while waiting for $method response: ${firstResponseResult.lastError ?: "unknown"}"
                )
            }
        }
        val responses = try {
            withTimeout(timeoutMillis) { deferred.await() }
        } catch (timeout: TimeoutCancellationException) {
            NwcLog.warn(logTag, timeout) { "Timed out waiting for responses to $method request event ${event.id}" }
            throw NwcTimeoutException("Timed out waiting for $method responses", timeout)
        } finally {
            pendingRequestManager.remove(event.id, deferred)
        }
        if (hasInterceptors) {
            responses.forEach { (_, response) ->
                interceptors.forEach { it.onResponse(method, response) }
            }
        }
        NwcLog.debug(logTag) { "Collected ${responses.size} responses for $method event ${event.id}" }
        return responses
    }

    private suspend fun publish(event: Event) {
        session.sessionRuntime().publish(event)
    }

    private fun buildRequestEvent(
        method: String,
        params: JsonObject,
        expirationSeconds: Long?
    ): Event {
        val body = buildJsonObject {
            put("method", method)
            put("params", params)
        }
        val serialized = json.encodeToString(JsonObject.serializer(), body)
        val encryption = activeEncryption
        val encrypted = encryptPayload(serialized, encryption)
        val builder = identity.newEventBuilder()
            .kind(REQUEST_KIND)
            .addTag(TAG_P, walletPublicKeyHex)
            .content(encrypted)
            .addTag(TAG_ENCRYPTION, encryption.wireName)
        expirationSeconds?.let {
            builder.addTag(TAG_EXPIRATION, it.toString())
        }
        return builder.build()
    }

    private suspend fun handleOutput(relay: String, output: RelaySessionOutput) {
        when (output) {
            is RelaySessionOutput.PublishAcknowledged -> handlePublishAcknowledged(relay, output.result)
            is RelaySessionOutput.EventReceived -> handleEvent(output.subscriptionId, output.event)
            is RelaySessionOutput.EndOfStoredEvents -> handleEndOfEvents(output.subscriptionId)
            is RelaySessionOutput.SubscriptionTerminated -> handleSubscriptionTerminated(output)
            is RelaySessionOutput.Notice -> NwcLog.info(logTag) { "Relay $relay notice: ${output.message}" }
            is RelaySessionOutput.Error -> NwcLog.error(logTag) { "Relay $relay error: ${output.error}" }
            is RelaySessionOutput.ConnectionStateChanged -> NwcLog.debug(logTag) {
                "Relay $relay connection snapshot ${output.snapshot}"
            }
            else -> Unit
        }
    }

    private suspend fun handleEvent(subscriptionId: SubscriptionId, event: Event) {
        when {
            // Route response events to processResponseEvent (handles both single and multi)
            event.kind == RESPONSE_KIND -> processResponseEvent(event)
            subscriptionId.value == SUBSCRIPTION_NOTIFICATIONS -> processNotificationEvent(event)
        }
    }

    private fun handleEndOfEvents(subscriptionId: SubscriptionId) {
        NwcLog.debug(logTag) { "Relay signaled end-of-stored-events for subscription ${subscriptionId.value}" }
    }

    private fun handleSubscriptionTerminated(message: RelaySessionOutput.SubscriptionTerminated) {
        val details = buildString {
            append("Relay terminated subscription ${message.subscriptionId.value}: ${message.reason}")
            message.code?.let { append(" (code=$it)") }
        }
        NwcLog.warn(logTag) { details }
    }

    private fun handlePublishAcknowledged(relay: String, result: PublishResult) {
        NwcLog.debug(logTag) {
            "Relay $relay acknowledged event ${result.eventId}: accepted=${result.accepted}, code=${result.code}, message='${result.message}'"
        }
        if (!result.accepted) {
            NwcLog.warn(logTag) {
                "Relay $relay rejected event ${result.eventId} with code=${result.code ?: "unknown"} message='${result.message}'"
            }
        }
    }

    private suspend fun processResponseEvent(event: Event, requestIdOverride: String? = null) {
        if (!event.isIntendedWalletMessage()) return
        val tagRequestId = requestIdOverride ?: event.tagValue(TAG_E)
        val rawResponse = runCatching { decodeResponse(event) }.getOrElse { failure ->
            val fallbackId = tagRequestId ?: pendingRequestManager.getSinglePendingId()
            if (fallbackId != null) {
                val error = NwcError("DECRYPTION_FAILED", failure.message ?: "Failed to decrypt response")
                NwcLog.error(logTag, failure) { "Failed to decrypt response for request $fallbackId" }
                completeRequestError(fallbackId, error)
            } else {
                NwcLog.error(logTag, failure) { "Dropping response; cannot decrypt and no request id" }
            }
            return
        }
        val requestId = tagRequestId
            ?: pendingRequestManager.resolveRequestId(rawResponse)
            ?: pendingRequestManager.getSinglePendingId()
            ?: run {
                NwcLog.warn(logTag) { "Dropping response missing #e tag; no unambiguous pending request" }
                return
            }

        // Try completing as single request first
        val singleResult = pendingRequestManager.completeSingle(requestId, rawResponse)
        if (singleResult is PendingRequestManager.CompletionResult.SingleCompleted) {
            return
        }

        // Try completing as multi request
        val key = event.tagValue(TAG_D) ?: deriveMultiKey(rawResponse)
        if (key != null) {
            pendingRequestManager.addMultiResponse(requestId, key, rawResponse)
        }
    }

    private fun deriveMultiKey(response: RawResponse): String? {
        val obj = response.result as? JsonObject ?: return null
        return obj.string("payment_hash")
    }

    private suspend fun completeRequestError(requestId: String, error: NwcError) {
        pendingRequestManager.completeWithError(requestId, error)
    }

    private fun decodeResponse(event: Event): RawResponse {
        if (event.kind != RESPONSE_KIND) {
            throw NwcProtocolException("Unexpected event kind ${event.kind} for response")
        }
        val selection = event.resolveEncryptionScheme()
        val plaintext = decryptWithSelection(event.content, selection)
        val element = json.parseToJsonElement(plaintext)
        val obj = element as? JsonObject ?: throw NwcProtocolException("Response payload must be JSON object")
        return decodeRawResponse(obj)
    }

    private fun processNotificationEvent(event: Event) {
        if (event.kind != NOTIFICATION_KIND) return
        if (!event.isIntendedWalletMessage()) return
        val selection = runCatching { event.resolveEncryptionScheme() }.getOrNull() ?: return
        val plaintext = runCatching { decryptWithSelection(event.content, selection) }.getOrNull() ?: return
        val element = runCatching { json.parseToJsonElement(plaintext) }.getOrNull() ?: return
        val obj = element as? JsonObject ?: return
        val type = obj.string("notification_type") ?: return
        val payload = obj["notification"] as? JsonObject ?: return
        val transaction = runCatching { parseTransaction(payload) }.getOrNull() ?: return
        val notification = when (type) {
            NotificationTypes.PAYMENT_RECEIVED -> WalletNotification.PaymentReceived(transaction)
            NotificationTypes.PAYMENT_SENT -> WalletNotification.PaymentSent(transaction)
            else -> null
        } ?: return
        if (hasInterceptors) {
            interceptors.forEach { it.onNotification(notification) }
        }
        _notifications.tryEmit(notification)
    }

    private fun parsePaymentResult(obj: JsonObject, method: String): PayInvoiceResult {
        val preimage = obj.string("preimage")
            ?: throw NwcProtocolException("$method response missing preimage")
        val feesPaid = obj["fees_paid"]?.jsonPrimitive?.longValueOrNull()
            ?.let { BitcoinAmount.fromMsats(it) }
        return PayInvoiceResult(preimage = preimage, feesPaid = feesPaid)
    }

    private fun encryptPayload(plaintext: String, scheme: EncryptionScheme): String = when (scheme) {
        EncryptionScheme.Nip44V2 -> nip44Encrypt(plaintext, nip44ConversationKey)
        EncryptionScheme.Nip04 -> nip04EncryptWithSharedSecret(plaintext, nip04SharedSecret)
        is EncryptionScheme.Unknown -> throw NwcEncryptionException("Unsupported encryption scheme: ${scheme.wireName}")
    }

    private fun decryptPayload(payload: String, scheme: EncryptionScheme): String = when (scheme) {
        EncryptionScheme.Nip44V2 -> nip44Decrypt(payload, nip44ConversationKey)
        EncryptionScheme.Nip04 -> nip04DecryptWithSharedSecret(payload, nip04SharedSecret)
        is EncryptionScheme.Unknown -> throw NwcEncryptionException("Unsupported encryption scheme: ${scheme.wireName}")
    }

    private fun Event.resolveEncryptionScheme(): ResolvedEncryption {
        val encryptionInfo = parseEncryptionTagValues(tagValues(TAG_ENCRYPTION))
        val negotiated = activeEncryptionOrDefault()

        val tagScheme = when {
            encryptionInfo.schemes.any { it is EncryptionScheme.Nip44V2 } -> EncryptionScheme.Nip44V2
            encryptionInfo.schemes.any { it is EncryptionScheme.Nip04 } -> EncryptionScheme.Nip04
            else -> null
        }

        return if (tagScheme != null) {
            ResolvedEncryption(tagScheme, fromTag = true)
        } else {
            ResolvedEncryption(negotiated, fromTag = false)
        }
    }

    private fun Event.isIntendedWalletMessage(): Boolean {
        // Must be from the expected wallet
        if (!pubkey.equals(walletPublicKeyHex, ignoreCase = true)) return false

        // If p tag exists, it must be addressed to this client
        val recipient = tagValue(TAG_P)
        if (recipient != null && !recipient.equals(clientPublicKeyHex, ignoreCase = true)) {
            return false
        }

        // Accept if from wallet and either has no p tag or p tag matches client
        return true
    }

    private fun decryptWithSelection(payload: String, selection: ResolvedEncryption): String {
        return runCatching { decryptPayload(payload, selection.scheme) }.getOrElse { primaryFailure ->
            if (!selection.fromTag && selection.scheme is EncryptionScheme.Nip44V2 && canFallbackToNip04()) {
                try {
                    return decryptPayload(payload, EncryptionScheme.Nip04)
                } catch (fallbackFailure: Throwable) {
                    primaryFailure.addSuppressed(fallbackFailure)
                    throw primaryFailure
                }
            } else {
                throw primaryFailure
            }
        }
    }

    private fun canFallbackToNip04(): Boolean =
        walletMetadataState.value?.encryptionSchemes?.any { it is EncryptionScheme.Nip04 } == true

    private fun randomId(): String {
        val bytes = ByteArray(8)
        Random.nextBytes(bytes)
        return bytes.toHexLower()
    }

    private data class ResolvedEncryption(
        val scheme: EncryptionScheme,
        val fromTag: Boolean
    )
}
