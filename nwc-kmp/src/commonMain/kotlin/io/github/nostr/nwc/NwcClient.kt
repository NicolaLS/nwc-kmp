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
import io.github.nostr.nwc.internal.defaultNwcHttpClient
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
import io.github.nostr.nwc.model.NwcResult
import io.github.nostr.nwc.model.NwcWalletDescriptor
import io.github.nostr.nwc.model.PayInvoiceParams
import io.github.nostr.nwc.model.PayInvoiceResult
import io.github.nostr.nwc.model.Transaction
import io.github.nostr.nwc.model.TransactionState
import io.github.nostr.nwc.model.TransactionType
import io.github.nostr.nwc.model.WalletMetadata
import io.github.nicolals.nostr.nips.nip42.Nip42Auth
import io.github.nostr.nwc.model.RawResponse
import io.github.nostr.nwc.model.WalletNotification
import io.ktor.client.HttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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
import nostr.core.utils.toHexLower
import io.github.nostr.nwc.internal.NwcSessionRuntime
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
private const val AUTH_REQUIRED_CODE = "auth-required"
private const val MAX_AUTH_RETRIES_PER_RELAY = 2
const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L

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

    companion object {
        suspend fun create(
            uri: String,
            scope: CoroutineScope,
            httpClient: HttpClient? = null,
            sessionSettings: RelaySessionSettings = RelaySessionSettings(),
            requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS,
            cachedMetadata: WalletMetadata? = null,
            cachedEncryption: EncryptionScheme? = null,
            interceptors: List<NwcClientInterceptor> = emptyList()
        ): NwcClient {
            return create(
                uri = NwcUri.parse(uri),
                scope = scope,
                httpClient = httpClient,
                sessionSettings = sessionSettings,
                requestTimeoutMillis = requestTimeoutMillis,
                cachedMetadata = cachedMetadata,
                cachedEncryption = cachedEncryption,
                interceptors = interceptors
            )
        }

        suspend fun create(
            uri: NwcUri,
            scope: CoroutineScope,
            httpClient: HttpClient? = null,
            sessionSettings: RelaySessionSettings = RelaySessionSettings(),
            requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS,
            cachedMetadata: WalletMetadata? = null,
            cachedEncryption: EncryptionScheme? = null,
            interceptors: List<NwcClientInterceptor> = emptyList()
        ): NwcClient {
            val credentials = uri.toCredentials()
            val (client, ownsClient) = httpClient?.let { it to false } ?: run {
                defaultNwcHttpClient() to true
            }
            val session = NwcSession.create(
                credentials = credentials,
                scope = scope,
                httpClient = client,
                sessionSettings = sessionSettings
            )
            return create(
                credentials = credentials,
                scope = scope,
                session = session,
                ownsSession = true,
                httpClient = client,
                ownsHttpClient = ownsClient,
                requestTimeoutMillis = requestTimeoutMillis,
                cachedMetadata = cachedMetadata,
                cachedEncryption = cachedEncryption,
                interceptors = interceptors
            )
        }

        suspend fun create(
            credentials: NwcCredentials,
            scope: CoroutineScope,
            httpClient: HttpClient? = null,
            sessionSettings: RelaySessionSettings = RelaySessionSettings(),
            requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS,
            cachedMetadata: WalletMetadata? = null,
            cachedEncryption: EncryptionScheme? = null,
            interceptors: List<NwcClientInterceptor> = emptyList()
        ): NwcClient {
            val (client, ownsClient) = httpClient?.let { it to false } ?: run {
                defaultNwcHttpClient() to true
            }
            val session = NwcSession.create(
                credentials = credentials,
                scope = scope,
                httpClient = client,
                sessionSettings = sessionSettings
            )
            return create(
                credentials = credentials,
                scope = scope,
                session = session,
                ownsSession = true,
                httpClient = client,
                ownsHttpClient = ownsClient,
                requestTimeoutMillis = requestTimeoutMillis,
                cachedMetadata = cachedMetadata,
                cachedEncryption = cachedEncryption,
                interceptors = interceptors
            )
        }

        suspend fun create(
            credentials: NwcCredentials,
            scope: CoroutineScope,
            session: NwcSession,
            ownsSession: Boolean,
            httpClient: HttpClient,
            ownsHttpClient: Boolean = false,
            requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS,
            cachedMetadata: WalletMetadata? = null,
            cachedEncryption: EncryptionScheme? = null,
            interceptors: List<NwcClientInterceptor> = emptyList()
        ): NwcClient {
            val client = NwcClient(
                credentials = credentials,
                scope = scope,
                requestTimeoutMillis = requestTimeoutMillis,
                session = session,
                ownsSession = ownsSession,
                httpClient = httpClient,
                ownsHttpClient = ownsHttpClient,
                interceptors = interceptors,
                initialMetadata = cachedMetadata,
                initialEncryption = cachedEncryption
            )
            client.initialize()
            return client
        }
    }

    private sealed interface PendingRequest {
        val method: String
        val event: Event
        val relayAttempts: MutableMap<String, Int>

        class Single(
            override val method: String,
            override val event: Event,
            val deferred: CompletableDeferred<RawResponse>,
            override val relayAttempts: MutableMap<String, Int> = mutableMapOf()
        ) : PendingRequest

        class Multi(
            override val method: String,
            override val event: Event,
            val expectedKeys: Set<String>,
            val results: MutableMap<String, RawResponse>,
            val deferred: CompletableDeferred<Map<String, RawResponse>>,
            override val relayAttempts: MutableMap<String, Int> = mutableMapOf()
        ) : PendingRequest
    }

    private data class RelayAuthState(
        var relayUrl: String? = null,
        var challenge: String? = null,
        var lastAuthEventId: String? = null,
        var lastAuthAccepted: Boolean? = null,
        var lastAuthMessage: String? = null,
        val pendingRequestIds: MutableSet<String> = mutableSetOf()
    )

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
        return preferred ?: EncryptionScheme.Nip44V2
    }

    private fun activeEncryptionOrDefault(): EncryptionScheme =
        if (activeEncryption !is EncryptionScheme.Unknown) {
            activeEncryption
        } else if (canFallbackToNip04()) {
            EncryptionScheme.Nip04
        } else {
            EncryptionScheme.Nip44V2
        }

    private val pendingMutex = Mutex()
    private val pendingRequests = mutableMapOf<String, PendingRequest>()

    private val authMutex = Mutex()
    private val relayAuthStates = mutableMapOf<String, RelayAuthState>()

    private val infoMutex = Mutex()
    private val infoSubscriptions = mutableMapOf<String, CompletableDeferred<Event?>>()

    private val _notifications = MutableSharedFlow<WalletNotification>(replay = 0, extraBufferCapacity = 64)
    override val notifications: SharedFlow<WalletNotification> = _notifications.asSharedFlow()

    private val walletMetadataState = MutableStateFlow<WalletMetadata?>(initialMetadata)
    override val walletMetadata: StateFlow<WalletMetadata?> = walletMetadataState.asStateFlow()

    private val responseFilter = Filter(
        kinds = setOf(RESPONSE_KIND),
        authors = setOf(walletPublicKeyHex),
        tags = mapOf("#$TAG_P" to setOf(clientPublicKeyHex))
    )

    private val notificationFilter = Filter(
        kinds = setOf(NOTIFICATION_KIND),
        authors = setOf(walletPublicKeyHex),
        tags = mapOf("#$TAG_P" to setOf(clientPublicKeyHex))
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
        pendingMutex.withLock {
            pendingRequests.values.forEach { request ->
                when (request) {
                    is PendingRequest.Single -> request.deferred.cancel()
                    is PendingRequest.Multi -> request.deferred.cancel()
                }
            }
            pendingRequests.clear()
        }
        infoMutex.withLock {
            infoSubscriptions.values.forEach { it.cancel() }
            infoSubscriptions.clear()
        }
        authMutex.withLock {
            relayAuthStates.clear()
        }
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
        session.runtimeHandles.forEach { handle ->
            val metadata = fetchMetadataFrom(handle, timeoutMillis)
            if (metadata != null) {
                walletMetadataState.value = metadata
                updateActiveEncryption(metadata)
                return metadata
            }
        }
        throw NwcException("Unable to fetch wallet metadata from configured relays.")
    }

    override suspend fun getBalance(timeoutMillis: Long): NwcResult<BalanceResult> =
        runNwcCatching { getBalanceInternal(timeoutMillis) }

    private suspend fun getBalanceInternal(timeoutMillis: Long): BalanceResult {
        val params = buildJsonObject { }
        val response = sendSingleRequest(MethodNames.GET_BALANCE, params, timeoutMillis)
        val jsonResult = response.result as? JsonObject
            ?: throw NwcProtocolException("get_balance response missing result object")
        val balanceMsats = jsonResult["balance"]?.jsonPrimitive?.longValueOrNull()
            ?: throw NwcProtocolException("get_balance response missing balance")
        return BalanceResult(BitcoinAmount.fromMsats(balanceMsats))
    }

    override suspend fun getInfo(timeoutMillis: Long): NwcResult<GetInfoResult> =
        runNwcCatching { getInfoInternal(timeoutMillis) }

    private suspend fun getInfoInternal(timeoutMillis: Long): GetInfoResult {
        val params = buildJsonObject { }
        val response = sendSingleRequest(MethodNames.GET_INFO, params, timeoutMillis)
        val obj = response.result as? JsonObject
            ?: throw NwcProtocolException("get_info response missing result object")
        val pubkey = obj.string("pubkey")
            ?: throw NwcProtocolException("get_info response missing pubkey")
        val network = Network.fromWire(obj.string("network"))
        val methods = obj.jsonArrayOrNull("methods")
            ?.mapNotNull { NwcCapability.fromWire(it.asString()) }
            ?.toSet()
            ?: emptySet()
        val notifications = obj.jsonArrayOrNull("notifications")
            ?.mapNotNull { NwcNotificationType.fromWire(it.asString()) }
            ?.toSet()
            ?: emptySet()
        return GetInfoResult(
            alias = obj.string("alias"),
            color = obj.string("color"),
            pubkey = pubkey,
            network = network,
            blockHeight = obj["block_height"]?.jsonPrimitive?.longValueOrNull(),
            blockHash = obj.string("block_hash"),
            methods = methods,
            notifications = notifications
        )
    }

    override suspend fun payInvoice(
        params: PayInvoiceParams,
        timeoutMillis: Long
    ): NwcResult<PayInvoiceResult> = runNwcCatching { payInvoiceInternal(params, timeoutMillis) }

    private suspend fun payInvoiceInternal(
        params: PayInvoiceParams,
        timeoutMillis: Long
    ): PayInvoiceResult {
        val payload = buildJsonObject {
            put("invoice", params.invoice)
            params.amount?.let { put("amount", it.msats) }
            params.metadata?.let { put("metadata", it) }
        }
        val response = sendSingleRequest(MethodNames.PAY_INVOICE, payload, timeoutMillis)
        val resultObject = response.result as? JsonObject
            ?: throw NwcProtocolException("pay_invoice response missing result object")
        val preimage = resultObject.string("preimage")
            ?: throw NwcProtocolException("pay_invoice response missing preimage")
        val feesPaid = resultObject["fees_paid"]?.jsonPrimitive?.longValueOrNull()
            ?.let { BitcoinAmount.fromMsats(it) }
        return PayInvoiceResult(preimage = preimage, feesPaid = feesPaid)
    }

    override suspend fun multiPayInvoice(
        invoices: List<MultiPayInvoiceItem>,
        timeoutMillis: Long
    ): NwcResult<Map<String, MultiResult<PayInvoiceResult>>> =
        runNwcCatching { multiPayInvoiceInternal(invoices, timeoutMillis) }

    private suspend fun multiPayInvoiceInternal(
        invoices: List<MultiPayInvoiceItem>,
        timeoutMillis: Long
    ): Map<String, MultiResult<PayInvoiceResult>> {
        require(invoices.isNotEmpty()) { "multiPayInvoice requires at least one invoice" }
        val normalized = invoices.map { item ->
            val id = item.id ?: randomId()
            NormalizedInvoiceItem(id, item.invoice, item.amount, item.metadata)
        }
        val params = buildJsonObject {
            put("invoices", buildJsonArray {
                normalized.forEach { invoice ->
                    add(buildJsonObject {
                        put("id", invoice.id)
                        put("invoice", invoice.invoice)
                        invoice.amount?.let { put("amount", it.msats) }
                        invoice.metadata?.let { put("metadata", it) }
                    })
                }
            })
        }
        val expectedIds = normalized.map { it.id }.toSet()
        val responses = sendMultiRequest(
            method = MethodNames.MULTI_PAY_INVOICE,
            params = params,
            expectedKeys = expectedIds,
            timeoutMillis = timeoutMillis
        )
        return responses.mapValues { (key, raw) ->
            raw.error?.let { MultiResult.Failure(it) } ?: run {
                val resultObject = raw.result as? JsonObject
                    ?: return@mapValues MultiResult.Failure(
                        NwcError("INVALID_RESULT", "Missing result payload for multi_pay_invoice entry $key")
                    )
                val preimage = resultObject.string("preimage")
                    ?: return@mapValues MultiResult.Failure(
                        NwcError("INVALID_RESULT", "Missing preimage for multi_pay_invoice entry $key")
                    )
                val feesPaid = resultObject["fees_paid"]?.jsonPrimitive?.longValueOrNull()
                    ?.let { BitcoinAmount.fromMsats(it) }
                MultiResult.Success(PayInvoiceResult(preimage, feesPaid))
            }
        }
    }

    override suspend fun payKeysend(
        params: KeysendParams,
        timeoutMillis: Long
    ): NwcResult<KeysendResult> = runNwcCatching { payKeysendInternal(params, timeoutMillis) }

    private suspend fun payKeysendInternal(
        params: KeysendParams,
        timeoutMillis: Long
    ): KeysendResult {
        val payload = buildJsonObject {
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
        val response = sendSingleRequest(MethodNames.PAY_KEYSEND, payload, timeoutMillis)
        val obj = response.result as? JsonObject
            ?: throw NwcProtocolException("pay_keysend response missing result object")
        val preimage = obj.string("preimage")
            ?: throw NwcProtocolException("pay_keysend response missing preimage")
        val feesPaid = obj["fees_paid"]?.jsonPrimitive?.longValueOrNull()
            ?.let { BitcoinAmount.fromMsats(it) }
        return KeysendResult(preimage, feesPaid)
    }

    override suspend fun multiPayKeysend(
        items: List<MultiKeysendItem>,
        timeoutMillis: Long
    ): NwcResult<Map<String, MultiResult<KeysendResult>>> =
        runNwcCatching { multiPayKeysendInternal(items, timeoutMillis) }

    private suspend fun multiPayKeysendInternal(
        items: List<MultiKeysendItem>,
        timeoutMillis: Long
    ): Map<String, MultiResult<KeysendResult>> {
        require(items.isNotEmpty()) { "multiPayKeysend requires at least one payment" }
        val normalized = items.map { item ->
            val id = item.id ?: randomId()
            NormalizedKeysendItem(
                id = id,
                pubkey = item.destinationPubkey,
                amount = item.amount,
                preimage = item.preimage,
                tlvRecords = item.tlvRecords
            )
        }
        val params = buildJsonObject {
            put("keysends", buildJsonArray {
                normalized.forEach { payment ->
                    add(buildJsonObject {
                        put("id", payment.id)
                        put("pubkey", payment.pubkey)
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
        val expected = normalized.map { it.id }.toSet()
        val responses = sendMultiRequest(
            method = MethodNames.MULTI_PAY_KEYSEND,
            params = params,
            expectedKeys = expected,
            timeoutMillis = timeoutMillis
        )
        return responses.mapValues { (key, raw) ->
            raw.error?.let { MultiResult.Failure(it) } ?: run {
                val obj = raw.result as? JsonObject
                    ?: return@mapValues MultiResult.Failure(
                        NwcError("INVALID_RESULT", "Missing result payload for multi_pay_keysend entry $key")
                    )
                val preimage = obj.string("preimage")
                    ?: return@mapValues MultiResult.Failure(
                        NwcError("INVALID_RESULT", "Missing preimage for multi_pay_keysend entry $key")
                    )
                val feesPaid = obj["fees_paid"]?.jsonPrimitive?.longValueOrNull()
                    ?.let { BitcoinAmount.fromMsats(it) }
                MultiResult.Success(KeysendResult(preimage, feesPaid))
            }
        }
    }

    override suspend fun makeInvoice(
        params: MakeInvoiceParams,
        timeoutMillis: Long
    ): NwcResult<Transaction> = runNwcCatching { makeInvoiceInternal(params, timeoutMillis) }

    private suspend fun makeInvoiceInternal(
        params: MakeInvoiceParams,
        timeoutMillis: Long
    ): Transaction {
        val payload = buildJsonObject {
            put("amount", params.amount.msats)
            params.description?.let { put("description", it) }
            params.descriptionHash?.let { put("description_hash", it) }
            params.expirySeconds?.let { put("expiry", it) }
            params.metadata?.let { put("metadata", it) }
        }
        val response = sendSingleRequest(MethodNames.MAKE_INVOICE, payload, timeoutMillis)
        val obj = response.result as? JsonObject
            ?: throw NwcProtocolException("make_invoice response missing result object")
        return parseTransaction(obj)
    }

    override suspend fun lookupInvoice(
        params: LookupInvoiceParams,
        timeoutMillis: Long
    ): NwcResult<Transaction> = runNwcCatching { lookupInvoiceInternal(params, timeoutMillis) }

    private suspend fun lookupInvoiceInternal(
        params: LookupInvoiceParams,
        timeoutMillis: Long
    ): Transaction {
        val payload = buildJsonObject {
            params.paymentHash?.let { put("payment_hash", it) }
            params.invoice?.let { put("invoice", it) }
        }
        val response = sendSingleRequest(MethodNames.LOOKUP_INVOICE, payload, timeoutMillis)
        val obj = response.result as? JsonObject
            ?: throw NwcProtocolException("lookup_invoice response missing result object")
        return parseTransaction(obj)
    }

    override suspend fun listTransactions(
        params: ListTransactionsParams,
        timeoutMillis: Long
    ): NwcResult<List<Transaction>> = runNwcCatching { listTransactionsInternal(params, timeoutMillis) }

    private suspend fun listTransactionsInternal(
        params: ListTransactionsParams,
        timeoutMillis: Long
    ): List<Transaction> {
        val payload = buildJsonObject {
            params.fromTimestamp?.let { put("from", it) }
            params.untilTimestamp?.let { put("until", it) }
            params.limit?.let { put("limit", it) }
            params.offset?.let { put("offset", it) }
            if (params.includeUnpaidInvoices) {
                put("unpaid", true)
            }
            params.type?.let { put("type", it.toWire()) }
        }
        val response = sendSingleRequest(MethodNames.LIST_TRANSACTIONS, payload, timeoutMillis)
        val obj = response.result as? JsonObject
            ?: throw NwcProtocolException("list_transactions response missing result object")
        val array = obj.jsonArrayOrNull("transactions") ?: JsonArray(emptyList())
        return array.mapNotNull { element ->
            val transactionObj = element as? JsonObject ?: return@mapNotNull null
            runCatching { parseTransaction(transactionObj) }.getOrNull()
        }
    }

    override suspend fun describeWallet(timeoutMillis: Long): NwcResult<NwcWalletDescriptor> =
        runNwcCatching { describeWalletInternal(timeoutMillis) }

    private suspend fun describeWalletInternal(timeoutMillis: Long): NwcWalletDescriptor {
        val metadata = refreshWalletMetadataInternal(timeoutMillis)
        val info = getInfoInternal(timeoutMillis)
        val negotiated = activeEncryption
        return NwcWalletDescriptor(
            uri = credentials.toUri(),
            metadata = metadata,
            info = info,
            negotiatedEncryption = negotiated,
            relays = credentials.relays,
            lud16 = credentials.lud16
        )
    }

    private suspend fun initialize() {
        require(credentials.relays.isNotEmpty()) { "No relays provided in credentials" }
        NwcLog.info(logTag) {
            "Opening NWC session for ${credentials.walletPublicKeyHex} across ${credentials.relays.size} relay(s)"
        }
        session.open(handleOutput = ::handleOutput) { relaySession, _ ->
            NwcLog.debug(logTag) { "Subscribing to response streams on relay ${relaySession.url}" }
            relaySession.subscribe(SUBSCRIPTION_RESPONSES, listOf(responseFilter))
            relaySession.subscribe(SUBSCRIPTION_NOTIFICATIONS, listOf(notificationFilter))
        }
        if (walletMetadataState.value == null) {
            NwcLog.debug(logTag) { "No cached metadata available, issuing initial describe_wallet request" }
            refreshWalletMetadataInternal(requestTimeoutMillis)
        }
    }

    private suspend fun fetchMetadataFrom(
        session: NwcSessionRuntime.SessionHandle,
        timeoutMillis: Long
    ): WalletMetadata? {
        val subscriptionId = "nwc-info-${randomId()}"
        val deferred = CompletableDeferred<Event?>()
        infoMutex.withLock { infoSubscriptions[subscriptionId] = deferred }
        val filter = Filter(
            kinds = setOf(INFO_EVENT_KIND),
            authors = setOf(walletPublicKeyHex),
            limit = 1
        )
        session.session.subscribe(subscriptionId, listOf(filter))
        val resultEvent = try {
            NwcLog.debug(logTag) { "Waiting for wallet metadata via subscription $subscriptionId on relay ${session.url}" }
            withTimeout(timeoutMillis) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            NwcLog.warn(logTag) { "Timed out waiting for wallet metadata on relay ${session.url}" }
            null
        } finally {
            infoMutex.withLock { infoSubscriptions.remove(subscriptionId) }
            session.session.unsubscribe(subscriptionId)
        }
        if (resultEvent != null) {
            NwcLog.debug(logTag) { "Received wallet metadata event ${resultEvent.id} from relay ${session.url}" }
        }
        return resultEvent?.let { parseWalletMetadata(it) }
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
        val deferred = CompletableDeferred<RawResponse>()
        registerPending(event.id, PendingRequest.Single(method, event, deferred))
        try {
            NwcLog.debug(logTag) { "Publishing $method request as event ${event.id}" }
            publish(event)
        } catch (failure: Throwable) {
            NwcLog.error(logTag, failure) { "Failed to publish $method request event ${event.id}" }
            removePending(event.id, deferred)
            throw NwcException("Failed to publish request $method", failure)
        }
        val response = awaitSingleResponse(method, event.id, deferred, timeoutMillis)
        NwcLog.debug(logTag) { "Received response for $method request event ${event.id}" }
        response.error?.let { throw NwcRequestException(it) }
        if (hasInterceptors) {
            interceptors.forEach { it.onResponse(method, response) }
        }
        return response
    }

    private suspend fun sendMultiRequest(
        method: String,
        params: JsonObject,
        expectedKeys: Set<String>,
        timeoutMillis: Long,
        expirationSeconds: Long? = null
    ): Map<String, RawResponse> {
        require(expectedKeys.isNotEmpty()) { "Multi request expected keys cannot be empty" }
        if (hasInterceptors) {
            interceptors.forEach { it.onRequest(method, params) }
        }
        val event = buildRequestEvent(method, params, expirationSeconds)
        val deferred = CompletableDeferred<Map<String, RawResponse>>()
        registerPending(
            event.id,
            PendingRequest.Multi(
                method = method,
                event = event,
                expectedKeys = expectedKeys,
                results = mutableMapOf(),
                deferred = deferred
            )
        )
        try {
            NwcLog.debug(logTag) { "Publishing multi request $method (event ${event.id}) expecting ${expectedKeys.size} keys" }
            publish(event)
        } catch (failure: Throwable) {
            NwcLog.error(logTag, failure) { "Failed to publish multi request $method event ${event.id}" }
            removePending(event.id, deferred)
            throw NwcException("Failed to publish multi request $method", failure)
        }
        val responses = try {
            withTimeout(timeoutMillis) { deferred.await() }
        } catch (timeout: TimeoutCancellationException) {
            NwcLog.warn(logTag, timeout) { "Timed out waiting for responses to $method request event ${event.id}" }
            throw NwcTimeoutException("Timed out waiting for $method responses", timeout)
        } finally {
            pendingMutex.withLock { pendingRequests.remove(event.id) }
        }
        if (hasInterceptors) {
            responses.forEach { (_, response) ->
                interceptors.forEach { it.onResponse(method, response) }
            }
        }
        NwcLog.debug(logTag) { "Collected ${responses.size} responses for $method event ${event.id}" }
        return responses
    }

    private suspend fun awaitSingleResponse(
        method: String,
        requestId: String,
        deferred: CompletableDeferred<RawResponse>,
        timeoutMillis: Long
    ): RawResponse =
        try {
            withTimeout(timeoutMillis) { deferred.await() }
        } catch (timeout: TimeoutCancellationException) {
            throw NwcTimeoutException("Timed out waiting for $method response", timeout)
        } finally {
            pendingMutex.withLock {
                val pending = pendingRequests[requestId]
                if (pending is PendingRequest.Single && pending.deferred == deferred) {
                    pendingRequests.remove(requestId)
                }
            }
        }

    private suspend fun registerPending(requestId: String, pending: PendingRequest) {
        pendingMutex.withLock {
            pendingRequests[requestId] = pending
        }
    }

    private suspend fun removePending(requestId: String, deferred: CompletableDeferred<*>) {
        pendingMutex.withLock {
            val current = pendingRequests[requestId]
            val matches = when {
                current is PendingRequest.Single && current.deferred == deferred -> true
                current is PendingRequest.Multi && current.deferred == deferred -> true
                else -> false
            }
            if (matches) {
                pendingRequests.remove(requestId)
            }
        }
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
            is RelaySessionOutput.AuthChallenge -> handleAuthChallenge(relay, output)
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
        val id = subscriptionId.value
        if (completeInfoRequest(id, event)) return
        when (id) {
            SUBSCRIPTION_RESPONSES -> processResponseEvent(event)
            SUBSCRIPTION_NOTIFICATIONS -> processNotificationEvent(event)
        }
    }

    private suspend fun handleEndOfEvents(subscriptionId: SubscriptionId) {
        val id = subscriptionId.value
        infoMutex.withLock {
            infoSubscriptions[id]?.takeIf { !it.isCompleted }?.complete(null)
        }
        NwcLog.debug(logTag) { "Relay signaled end-of-stored-events for subscription $id" }
    }

    private suspend fun handleSubscriptionTerminated(message: RelaySessionOutput.SubscriptionTerminated) {
        val id = message.subscriptionId.value
        infoMutex.withLock {
            infoSubscriptions[id]?.takeIf { !it.isCompleted }?.complete(null)
        }
        val details = buildString {
            append("Relay terminated subscription $id: ${message.reason}")
            message.code?.let { append(" (code=$it)") }
        }
        NwcLog.warn(logTag) { details }
    }

    private suspend fun handleAuthChallenge(relay: String, challenge: RelaySessionOutput.AuthChallenge) {
        val relayUrl = challenge.relayUrl?.takeUnless { it.isBlank() } ?: relay
        NwcLog.info(logTag) { "Relay $relay issued NIP-42 challenge '${challenge.challenge.take(16)}${if (challenge.challenge.length > 16) "…" else ""}'" }
        val pendingRequestIds = authMutex.withLock {
            val state = relayAuthStates.getOrPut(relay) { RelayAuthState() }
            state.relayUrl = relayUrl
            state.challenge = challenge.challenge
            val pending = state.pendingRequestIds.toList()
            state.pendingRequestIds.clear()
            pending
        }
        val authEvent = try {
            Nip42Auth.buildAuthEvent(
                signer = identity,
                relayUrl = relayUrl,
                challenge = challenge.challenge
            )
        } catch (_: Throwable) {
            authMutex.withLock {
                relayAuthStates[relay]?.pendingRequestIds?.addAll(pendingRequestIds)
            }
            NwcLog.error(logTag) { "Failed to build NIP-42 auth event for relay $relay" }
            return
        }
        try {
            authMutex.withLock {
                val state = relayAuthStates.getOrPut(relay) { RelayAuthState() }
                state.lastAuthEventId = authEvent.id
                state.lastAuthAccepted = null
                state.lastAuthMessage = null
            }
            NwcLog.debug(logTag) { "Dispatching auth event ${authEvent.id} to relay $relay" }
            session.sessionRuntime().authenticate(relay, authEvent)
        } catch (_: Throwable) {
            authMutex.withLock {
                relayAuthStates[relay]?.pendingRequestIds?.addAll(pendingRequestIds)
            }
            NwcLog.warn(logTag) { "Failed to send auth event ${authEvent.id} to relay $relay; will retry when possible" }
            return
        }
        if (pendingRequestIds.isNotEmpty()) {
            NwcLog.debug(logTag) { "Retrying ${pendingRequestIds.size} pending request(s) after auth on relay $relay" }
            resendPendingRequests(relay, pendingRequestIds)
        }
    }

    private suspend fun handlePublishAcknowledged(relay: String, result: PublishResult) {
        NwcLog.debug(logTag) {
            "Relay $relay acknowledged event ${result.eventId}: accepted=${result.accepted}, code=${result.code}, message='${result.message}'"
        }
        val handledAuth = authMutex.withLock {
            val state = relayAuthStates[relay]
            if (state?.lastAuthEventId == result.eventId) {
                state.lastAuthAccepted = result.accepted
                state.lastAuthMessage = result.message
                if (!result.accepted) {
                    state.challenge = null
                }
                val status = if (result.accepted) "accepted" else "rejected"
                NwcLog.info(logTag) { "Relay $relay $status auth event ${result.eventId}: ${result.message}" }
                true
            } else {
                false
            }
        }
        if (handledAuth) return
        val requiresAuth = !result.accepted && result.code?.equals(AUTH_REQUIRED_CODE, ignoreCase = true) == true
        if (requiresAuth) {
            NwcLog.debug(logTag) { "Relay $relay rejected publish ${result.eventId} with auth-required" }
            handleAuthRequired(relay, result)
        } else if (!result.accepted) {
            NwcLog.warn(logTag) {
                "Relay $relay rejected event ${result.eventId} with code=${result.code ?: "unknown"} message='${result.message}'"
            }
        }
    }

    private suspend fun handleAuthRequired(relay: String, result: PublishResult) {
        var currentAttempts = 0
        val hasPending = pendingMutex.withLock {
            val pending = pendingRequests[result.eventId] as? PendingRequest ?: return@withLock false
            val attempts = pending.relayAttempts[relay] ?: 0
            if (attempts >= MAX_AUTH_RETRIES_PER_RELAY) {
                NwcLog.warn(logTag) { "Relay $relay still requires auth for event ${result.eventId} after $attempts attempts; giving up" }
                false
            } else {
                currentAttempts = attempts
                true
            }
        }
        if (!hasPending) return

        var resolvedRelayUrl = relay
        val challenge = authMutex.withLock {
            val state = relayAuthStates.getOrPut(relay) { RelayAuthState() }
            resolvedRelayUrl = state.relayUrl ?: relay
            val challengeValue = state.challenge
            if (challengeValue == null) {
                state.pendingRequestIds.add(result.eventId)
                NwcLog.debug(logTag) { "Queued request ${result.eventId} until challenge arrives for relay $relay" }
            }
            challengeValue
        } ?: return

        val authEvent = try {
            Nip42Auth.buildAuthEvent(
                signer = identity,
                relayUrl = resolvedRelayUrl,
                challenge = challenge
            )
        } catch (_: Throwable) {
            return
        }

        try {
            authMutex.withLock {
                val state = relayAuthStates.getOrPut(relay) { RelayAuthState() }
                state.lastAuthEventId = authEvent.id
                state.lastAuthAccepted = null
                state.lastAuthMessage = null
            }
            session.sessionRuntime().authenticate(relay, authEvent)
        } catch (_: Throwable) {
            NwcLog.warn(logTag) { "Failed to send retry auth event for relay $relay" }
            authMutex.withLock {
                relayAuthStates[relay]?.pendingRequestIds?.add(result.eventId)
            }
            return
        }

        pendingMutex.withLock {
            (pendingRequests[result.eventId] as? PendingRequest)?.relayAttempts?.set(relay, currentAttempts + 1)
        }
        NwcLog.debug(logTag) { "Auth attempt ${currentAttempts + 1} dispatched for event ${result.eventId} on relay $relay" }
        resendPendingRequests(relay, listOf(result.eventId))
    }

    private suspend fun resendPendingRequests(relay: String, requestIds: List<String>) {
        for (requestId in requestIds) {
            val event = pendingMutex.withLock {
                (pendingRequests[requestId] as? PendingRequest)?.event
            } ?: continue
            runCatching {
                NwcLog.debug(logTag) { "Replaying request event $requestId to relay $relay" }
                session.sessionRuntime().publishTo(relay, event)
            }.onFailure {
                NwcLog.warn(logTag, it) { "Failed to replay request event $requestId to relay $relay" }
            }
        }
    }

    private suspend fun completeInfoRequest(subscriptionId: String, event: Event): Boolean {
        val deferred = infoMutex.withLock { infoSubscriptions[subscriptionId] } ?: return false
        if (!deferred.isCompleted) {
            deferred.complete(event)
        }
        return true
    }

    private suspend fun processResponseEvent(event: Event) {
        val requestId = event.tagValue(TAG_E) ?: return
        val rawResponse = runCatching { decodeResponse(event) }.getOrElse { failure ->
            val error = NwcError("DECRYPTION_FAILED", failure.message ?: "Failed to decrypt response")
            NwcLog.error(logTag, failure) { "Failed to decrypt response for request ${event.tagValue(TAG_E)}" }
            completeRequestError(requestId, error)
            return
        }
        pendingMutex.withLock {
            when (val pending = pendingRequests[requestId]) {
                is PendingRequest.Single -> {
                    if (!pending.deferred.isCompleted) {
                        NwcLog.debug(logTag) { "Completing single response for event $requestId" }
                        pending.deferred.complete(rawResponse)
                    }
                }
                is PendingRequest.Multi -> {
                    val key = event.tagValue(TAG_D) ?: deriveMultiKey(rawResponse)
                    if (key != null && !pending.results.containsKey(key)) {
                        pending.results[key] = rawResponse
                        if (pending.results.keys.containsAll(pending.expectedKeys)) {
                            NwcLog.debug(logTag) { "Collected final multi response for event $requestId" }
                            pending.deferred.complete(pending.results.toMap())
                            pendingRequests.remove(requestId)
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    private fun deriveMultiKey(response: RawResponse): String? {
        val obj = response.result as? JsonObject ?: return null
        return obj.string("payment_hash")
    }

    private suspend fun completeRequestError(requestId: String, error: NwcError) {
        pendingMutex.withLock {
            when (val pending = pendingRequests.remove(requestId)) {
                is PendingRequest.Single -> {
                    NwcLog.warn(logTag) { "Completing request $requestId with error ${error.code}" }
                    pending.deferred.complete(RawResponse("", null, error))
                }
                is PendingRequest.Multi -> {
                    NwcLog.warn(logTag) { "Completing multi request $requestId with error ${error.code}" }
                    pending.deferred.complete(
                        pending.expectedKeys.associateWith { RawResponse("", null, error) }
                    )
                }
                else -> Unit
            }
        }
    }

    private fun decodeResponse(event: Event): RawResponse {
        if (event.kind != RESPONSE_KIND) {
            throw NwcProtocolException("Unexpected event kind ${event.kind} for response")
        }
        val selection = event.resolveEncryptionScheme()
        val plaintext = decryptWithSelection(event.content, selection)
        val element = json.parseToJsonElement(plaintext)
        val obj = element as? JsonObject ?: throw NwcProtocolException("Response payload must be JSON object")
        val resultType = obj["result_type"]?.jsonPrimitive?.content
            ?: throw NwcProtocolException("Response missing result_type")
        val error = parseError(obj["error"])
        val result = obj["result"]
        return RawResponse(resultType, result, error)
    }

    private fun parseError(element: JsonElement?): NwcError? {
        if (element == null || element is JsonNull) return null
        val obj = element as? JsonObject ?: return null
        val code = obj.string("code") ?: return null
        val message = obj.string("message") ?: ""
        return NwcError(code, message)
    }

    private fun processNotificationEvent(event: Event) {
        if (event.kind != NOTIFICATION_KIND) return
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
        if (!_notifications.tryEmit(notification)) {
            scope.launch { _notifications.emit(notification) }
        }
    }

    private fun parseTransaction(source: JsonObject): Transaction {
        val type = TransactionType.fromWire(source.string("type"))
            ?: throw NwcProtocolException("Transaction missing type")
        val paymentHash = source.string("payment_hash")
            ?: throw NwcProtocolException("Transaction missing payment_hash")
        val amountMsats = source["amount"]?.jsonPrimitive?.longValueOrNull()
            ?: throw NwcProtocolException("Transaction missing amount")
        val createdAt = source["created_at"]?.jsonPrimitive?.longValueOrNull()
            ?: throw NwcProtocolException("Transaction missing created_at")
        val state = TransactionState.fromWire(source.string("state"))
        val feesPaid = source["fees_paid"]?.jsonPrimitive?.longValueOrNull()?.let { BitcoinAmount.fromMsats(it) }
        val metadata = source.jsonObjectOrNull("metadata")
        return Transaction(
            type = type,
            state = state,
            invoice = source.string("invoice"),
            description = source.string("description"),
            descriptionHash = source.string("description_hash"),
            preimage = source.string("preimage"),
            paymentHash = paymentHash,
            amount = BitcoinAmount.fromMsats(amountMsats),
            feesPaid = feesPaid,
            createdAt = createdAt,
            expiresAt = source["expires_at"]?.jsonPrimitive?.longValueOrNull(),
            settledAt = source["settled_at"]?.jsonPrimitive?.longValueOrNull(),
            metadata = metadata
        )
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

    private fun parseWalletMetadata(event: Event): WalletMetadata {
        val capabilityValues = event.content
            .split(' ', '\n', '\t')
            .mapNotNull { it.trim().takeIf { it.isNotEmpty() } }
        val capabilities = NwcCapability.parseAll(capabilityValues)
        val encryptionInfo = parseEncryptionTagValues(event.tagValues(TAG_ENCRYPTION))
        val encryptionSchemes = when {
            encryptionInfo.schemes.isEmpty() && encryptionInfo.defaultedToNip04 -> setOf(EncryptionScheme.Nip04)
            encryptionInfo.schemes.isNotEmpty() -> encryptionInfo.schemes.toSet()
            else -> emptySet()
        }
        val notificationsTag = event.tags.firstOrNull { it.firstOrNull() == "notifications" }
        val notificationValues = notificationsTag?.getOrNull(1)
            ?.split(' ')
            ?.mapNotNull { it.trim().takeIf { it.isNotEmpty() } }
            ?: emptyList()
        val notificationTypes = NwcNotificationType.parseAll(notificationValues)
        return WalletMetadata(capabilities, encryptionSchemes, notificationTypes, encryptionInfo.defaultedToNip04)
    }

    private fun TransactionType.toWire(): String = when (this) {
        TransactionType.INCOMING -> "incoming"
        TransactionType.OUTGOING -> "outgoing"
    }

    private fun Event.tagValue(name: String): String? =
        tags.firstOrNull { it.isNotEmpty() && it[0] == name }?.getOrNull(1)

    private fun Event.tagValues(name: String): List<String>? =
        tags.firstOrNull { it.isNotEmpty() && it[0] == name }
            ?.drop(1)
            ?.takeIf { it.isNotEmpty() }

    private fun decryptWithSelection(payload: String, selection: ResolvedEncryption): String {
        return runCatching { decryptPayload(payload, selection.scheme) }.getOrElse { primaryFailure ->
            if (!selection.fromTag && selection.scheme is EncryptionScheme.Nip44V2 && canFallbackToNip04()) {
                try {
                    return decryptPayload(payload, EncryptionScheme.Nip04)
                } catch (fallbackFailure: Throwable) {
                    throw fallbackFailure
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

    private data class NormalizedInvoiceItem(
        val id: String,
        val invoice: String,
        val amount: BitcoinAmount?,
        val metadata: JsonObject?
    )

    private data class NormalizedKeysendItem(
        val id: String,
        val pubkey: String,
        val amount: BitcoinAmount,
        val preimage: String?,
        val tlvRecords: List<KeysendTlvRecord>
    )

    private data class ResolvedEncryption(
        val scheme: EncryptionScheme,
        val fromTag: Boolean
    )
}

class NwcProtocolException(message: String) : NwcException(message)
