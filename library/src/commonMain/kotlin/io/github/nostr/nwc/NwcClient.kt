@file:OptIn(ExperimentalUnsignedTypes::class)

package io.github.nostr.nwc

import io.github.nostr.nwc.internal.ENCRYPTION_SCHEME_NIP44
import io.github.nostr.nwc.internal.MethodNames
import io.github.nostr.nwc.internal.NotificationTypes
import io.github.nostr.nwc.internal.TAG_D
import io.github.nostr.nwc.internal.TAG_E
import io.github.nostr.nwc.internal.TAG_ENCRYPTION
import io.github.nostr.nwc.internal.TAG_EXPIRATION
import io.github.nostr.nwc.internal.TAG_P
import io.github.nostr.nwc.internal.asString
import io.github.nostr.nwc.internal.defaultNwcHttpClient
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
import io.github.nostr.nwc.model.PayInvoiceParams
import io.github.nostr.nwc.model.PayInvoiceResult
import io.github.nostr.nwc.model.Transaction
import io.github.nostr.nwc.model.TransactionState
import io.github.nostr.nwc.model.TransactionType
import io.github.nostr.nwc.model.WalletMetadata
import io.github.nostr.nwc.model.WalletNotification
import io.github.nostr.nwc.model.RawResponse
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
import nostr.core.model.SubscriptionId
import nostr.core.session.RelaySessionOutput
import nostr.core.session.RelaySessionSettings
import nostr.crypto.Identity as SecpIdentity
import calculateConversationKey as nip44CalculateConversationKey
import decrypt as nip44Decrypt
import ensureSodium as nip44EnsureSodium
import encrypt as nip44Encrypt
import kotlin.random.Random
import nostr.core.utils.toHexLower
import io.github.nostr.nwc.internal.NwcSessionRuntime
import io.github.nostr.nwc.internal.json
import io.github.nostr.nwc.internal.jsonArrayOrNull
import io.github.nostr.nwc.internal.jsonObjectOrNull
import io.github.nostr.nwc.internal.longValueOrNull
import io.github.nostr.nwc.internal.string

private const val SUBSCRIPTION_RESPONSES = "nwc-responses"
private const val SUBSCRIPTION_NOTIFICATIONS = "nwc-notifications"
private const val INFO_EVENT_KIND = 13194
private const val REQUEST_KIND = 23194
private const val RESPONSE_KIND = 23195
private const val NOTIFICATION_KIND = 23197
private const val DEFAULT_TIMEOUT_MILLIS = 30_000L

class NwcClient private constructor(
    private val credentials: NwcCredentials,
    private val scope: CoroutineScope,
    private val requestTimeoutMillis: Long,
    private val session: NwcSession,
    private val httpClient: HttpClient,
    private val ownsHttpClient: Boolean
) {

    companion object {
        suspend fun create(
            uri: String,
            scope: CoroutineScope,
            httpClient: HttpClient? = null,
            sessionSettings: RelaySessionSettings = RelaySessionSettings(),
            requestTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
        ): NwcClient {
            return create(
                uri = NwcUri.parse(uri),
                scope = scope,
                httpClient = httpClient,
                sessionSettings = sessionSettings,
                requestTimeoutMillis = requestTimeoutMillis
            )
        }

        suspend fun create(
            uri: NwcUri,
            scope: CoroutineScope,
            httpClient: HttpClient? = null,
            sessionSettings: RelaySessionSettings = RelaySessionSettings(),
            requestTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
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
            return create(credentials, scope, session, client, ownsClient, requestTimeoutMillis)
        }

        suspend fun create(
            credentials: NwcCredentials,
            scope: CoroutineScope,
            httpClient: HttpClient? = null,
            sessionSettings: RelaySessionSettings = RelaySessionSettings(),
            requestTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
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
            return create(credentials, scope, session, client, ownsClient, requestTimeoutMillis)
        }

        suspend fun create(
            credentials: NwcCredentials,
            scope: CoroutineScope,
            session: NwcSession,
            httpClient: HttpClient,
            ownsHttpClient: Boolean = false,
            requestTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
        ): NwcClient {
            val client = NwcClient(
                credentials = credentials,
                scope = scope,
                requestTimeoutMillis = requestTimeoutMillis,
                session = session,
                httpClient = httpClient,
                ownsHttpClient = ownsHttpClient
            )
            client.initialize()
            return client
        }

        private fun defaultHttpClient(): HttpClient = defaultNwcHttpClient()
    }

    private sealed interface PendingRequest {
        class Single(
            val method: String,
            val deferred: CompletableDeferred<RawResponse>
        ) : PendingRequest

        class Multi(
            val method: String,
            val expectedKeys: Set<String>,
            val results: MutableMap<String, RawResponse>,
            val deferred: CompletableDeferred<Map<String, RawResponse>>
        ) : PendingRequest
    }

    private val identity: Identity = SecpIdentity.fromPrivateKey(credentials.secretKey)
    private val clientPublicKeyHex: String = identity.publicKey.toString()
    private val walletPublicKeyHex: String = credentials.walletPublicKey.toString()
    private val conversationKey: UByteArray

    private val sessionRuntime = NwcSessionRuntime(
        scope = scope,
        httpClient = httpClient,
        wireCodec = wireCodec,
        sessionSettings = sessionSettings
    )

    private val pendingMutex = Mutex()
    private val pendingRequests = mutableMapOf<String, PendingRequest>()

    private val infoMutex = Mutex()
    private val infoSubscriptions = mutableMapOf<String, CompletableDeferred<Event?>>()

    private val _notifications = MutableSharedFlow<WalletNotification>(replay = 0, extraBufferCapacity = 64)
    val notifications: SharedFlow<WalletNotification> = _notifications.asSharedFlow()

    private val walletMetadataState = MutableStateFlow<WalletMetadata?>(null)
    val walletMetadata: StateFlow<WalletMetadata?> = walletMetadataState.asStateFlow()

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
        conversationKey = nip44CalculateConversationKey(
            credentials.secretKey.toByteArray(),
            credentials.walletPublicKey.toByteArray()
        )
    }

    suspend fun close() {
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
        session.close()
        if (ownsHttpClient) {
            runCatching { httpClient.close() }
        }
    }

    suspend fun refreshWalletMetadata(timeoutMillis: Long = requestTimeoutMillis): WalletMetadata {
        session.runtimeHandles.forEach { handle ->
            val metadata = fetchMetadataFrom(handle, timeoutMillis)
            if (metadata != null) {
                walletMetadataState.value = metadata
                if (EncryptionScheme.Nip44V2 !in metadata.encryptionSchemes) {
                    throw NwcEncryptionException("Wallet does not advertise nip44_v2 support.")
                }
                return metadata
            }
        }
        throw NwcException("Unable to fetch wallet metadata from configured relays.")
    }

    suspend fun getBalance(timeoutMillis: Long = requestTimeoutMillis): BalanceResult {
        val params = buildJsonObject { }
        val response = sendSingleRequest(MethodNames.GET_BALANCE, params, timeoutMillis)
        val jsonResult = response.result as? JsonObject
            ?: throw NwcProtocolException("get_balance response missing result object")
        val balanceMsats = jsonResult["balance"]?.jsonPrimitive?.longValueOrNull()
            ?: throw NwcProtocolException("get_balance response missing balance")
        return BalanceResult(BitcoinAmount.fromMsats(balanceMsats))
    }

    suspend fun getInfo(timeoutMillis: Long = requestTimeoutMillis): GetInfoResult {
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

    suspend fun payInvoice(
        params: PayInvoiceParams,
        timeoutMillis: Long = requestTimeoutMillis
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

    suspend fun multiPayInvoice(
        invoices: List<MultiPayInvoiceItem>,
        timeoutMillis: Long = requestTimeoutMillis
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

    suspend fun payKeysend(
        params: KeysendParams,
        timeoutMillis: Long = requestTimeoutMillis
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

    suspend fun multiPayKeysend(
        payments: List<MultiKeysendItem>,
        timeoutMillis: Long = requestTimeoutMillis
    ): Map<String, MultiResult<KeysendResult>> {
        require(payments.isNotEmpty()) { "multiPayKeysend requires at least one payment" }
        val normalized = payments.map { item ->
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

    suspend fun makeInvoice(
        params: MakeInvoiceParams,
        timeoutMillis: Long = requestTimeoutMillis
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

    suspend fun lookupInvoice(
        params: LookupInvoiceParams,
        timeoutMillis: Long = requestTimeoutMillis
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

    suspend fun listTransactions(
        params: ListTransactionsParams,
        timeoutMillis: Long = requestTimeoutMillis
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

    private suspend fun initialize() {
        require(credentials.relays.isNotEmpty()) { "No relays provided in credentials" }
        session.open(handleOutput = ::handleOutput) { runtime, _ ->
            runtime.subscribe(SUBSCRIPTION_RESPONSES, listOf(responseFilter))
            runtime.subscribe(SUBSCRIPTION_NOTIFICATIONS, listOf(notificationFilter))
        }
        refreshWalletMetadata()
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
        session.runtime.subscribe(subscriptionId, listOf(filter))
        val resultEvent = try {
            withTimeout(timeoutMillis) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            null
        } finally {
            infoMutex.withLock { infoSubscriptions.remove(subscriptionId) }
            session.runtime.unsubscribe(subscriptionId)
        }
        return resultEvent?.let { parseWalletMetadata(it) }
    }

    private suspend fun sendSingleRequest(
        method: String,
        params: JsonObject,
        timeoutMillis: Long,
        expirationSeconds: Long? = null
    ): RawResponse {
        val event = buildRequestEvent(method, params, expirationSeconds)
        val deferred = CompletableDeferred<RawResponse>()
        registerPending(event.id, PendingRequest.Single(method, deferred))
        try {
            publish(event)
        } catch (failure: Throwable) {
            removePending(event.id, deferred)
            throw NwcException("Failed to publish request $method", failure)
        }
        val response = awaitSingleResponse(method, event.id, deferred, timeoutMillis)
        response.error?.let { throw NwcRequestException(it) }
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
        val event = buildRequestEvent(method, params, expirationSeconds)
        val deferred = CompletableDeferred<Map<String, RawResponse>>()
        registerPending(
            event.id,
            PendingRequest.Multi(
                method = method,
                expectedKeys = expectedKeys,
                results = mutableMapOf(),
                deferred = deferred
            )
        )
        try {
            publish(event)
        } catch (failure: Throwable) {
            removePending(event.id, deferred)
            throw NwcException("Failed to publish multi request $method", failure)
        }
        return try {
            withTimeout(timeoutMillis) { deferred.await() }
        } catch (timeout: TimeoutCancellationException) {
            throw NwcTimeoutException("Timed out waiting for $method responses", timeout)
        } finally {
            pendingMutex.withLock { pendingRequests.remove(event.id) }
        }
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
        val encrypted = nip44Encrypt(serialized, conversationKey)
        val builder = identity.newEventBuilder()
            .kind(REQUEST_KIND)
            .addTag(TAG_ENCRYPTION, ENCRYPTION_SCHEME_NIP44)
            .addTag(TAG_P, walletPublicKeyHex)
            .content(encrypted)
        expirationSeconds?.let {
            builder.addTag(TAG_EXPIRATION, it.toString())
        }
        return builder.build()
    }

    private suspend fun handleOutput(relay: String, output: RelaySessionOutput) {
        when (output) {
            is RelaySessionOutput.EventReceived -> handleEvent(output.subscriptionId, output.event)
            is RelaySessionOutput.EndOfStoredEvents -> handleEndOfEvents(output.subscriptionId)
            is RelaySessionOutput.SubscriptionTerminated -> handleSubscriptionTerminated(output.subscriptionId)
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
    }

    private suspend fun handleSubscriptionTerminated(subscriptionId: SubscriptionId) {
        val id = subscriptionId.value
        infoMutex.withLock {
            infoSubscriptions[id]?.takeIf { !it.isCompleted }?.complete(null)
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
            completeRequestError(requestId, error)
            return
        }
        pendingMutex.withLock {
            when (val pending = pendingRequests[requestId]) {
                is PendingRequest.Single -> {
                    if (!pending.deferred.isCompleted) {
                        pending.deferred.complete(rawResponse)
                    }
                }
                is PendingRequest.Multi -> {
                    val key = event.tagValue(TAG_D) ?: deriveMultiKey(rawResponse)
                    if (key != null && !pending.results.containsKey(key)) {
                        pending.results[key] = rawResponse
                        if (pending.results.keys.containsAll(pending.expectedKeys)) {
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
                is PendingRequest.Single -> pending.deferred.complete(RawResponse("", null, error))
                is PendingRequest.Multi -> pending.deferred.complete(
                    pending.expectedKeys.associateWith { RawResponse("", null, error) }
                )
                else -> Unit
            }
        }
    }

    private fun decodeResponse(event: Event): RawResponse {
        if (event.kind != RESPONSE_KIND) {
            throw NwcProtocolException("Unexpected event kind ${event.kind} for response")
        }
        val plaintext = nip44Decrypt(event.content, conversationKey)
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
        val plaintext = runCatching { nip44Decrypt(event.content, conversationKey) }.getOrNull() ?: return
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

    private fun parseWalletMetadata(event: Event): WalletMetadata {
        val capabilityValues = event.content
            .split(' ', '\n', '\t')
            .mapNotNull { it.trim().takeIf { it.isNotEmpty() } }
        val capabilities = NwcCapability.parseAll(capabilityValues)
        val encryptionTag = event.tagValue(TAG_ENCRYPTION)
        val encryptionSchemes = EncryptionScheme.parseList(encryptionTag?.replace(',', ' '))
        val notificationsTag = event.tags.firstOrNull { it.firstOrNull() == "notifications" }
        val notificationValues = notificationsTag?.getOrNull(1)
            ?.split(' ')
            ?.mapNotNull { it.trim().takeIf { it.isNotEmpty() } }
            ?: emptyList()
        val notificationTypes = NwcNotificationType.parseAll(notificationValues)
        return WalletMetadata(capabilities, encryptionSchemes, notificationTypes)
    }

    private fun TransactionType.toWire(): String = when (this) {
        TransactionType.INCOMING -> "incoming"
        TransactionType.OUTGOING -> "outgoing"
    }

    private fun Event.tagValue(name: String): String? =
        tags.firstOrNull { it.isNotEmpty() && it[0] == name }?.getOrNull(1)

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
}

class NwcProtocolException(message: String) : NwcException(message)
