# API Reference

This page summarizes the public API exposed by `io.github.nicolals.nwc`.

## `NwcClient`

Factories:

```kotlin
NwcClient(
    uri: NwcConnectionUri,
    scope: CoroutineScope,
    httpClient: HttpClient? = null,
    cachedWalletInfo: WalletInfo? = null,
)

NwcClient.fromUri(
    uriString: String,
    scope: CoroutineScope,
    httpClient: HttpClient? = null,
    cachedWalletInfo: WalletInfo? = null,
): NwcClient?

NwcClient.fromDeepLink(
    deepLinkUri: String,
    scope: CoroutineScope,
    httpClient: HttpClient? = null,
    cachedWalletInfo: WalletInfo? = null,
): NwcClient?
```

Discovery methods on `NwcClient`:

```kotlin
suspend fun discover(
    uri: String,
    httpClient: HttpClient? = null,
    timeoutMs: Long = 10_000,
): NwcResult<WalletDiscovery>

suspend fun discoverFromDeepLink(
    deepLinkUri: String,
    httpClient: HttpClient? = null,
    timeoutMs: Long = 10_000,
): NwcResult<WalletDiscovery>
```

State:

```kotlin
val state: StateFlow<NwcClientState>
val walletInfo: StateFlow<WalletInfo>
val notifications: SharedFlow<WalletNotification>
val uri: NwcConnectionUri
val encryption: NwcEncryption
val pendingRequestCount: Int
```

Lifecycle:

```kotlin
fun connect(
    forceReconnect: Boolean = false,
    connectionTimeoutMs: Long = 10_000,
)

suspend fun awaitReady(timeoutMs: Long = 10_000): Boolean

fun close()
```

Operations:

```kotlin
suspend fun getBalance(timeoutMs: Long = 30_000): NwcResult<Amount>

suspend fun getInfo(timeoutMs: Long = 30_000): NwcResult<WalletDetails>

suspend fun payInvoice(
    invoice: String,
    amount: Amount? = null,
    timeoutMs: Long = 60_000,
    verifyOnTimeout: Boolean = true,
): NwcResult<PaymentResult>

suspend fun payInvoice(
    params: PayInvoiceParams,
    timeoutMs: Long = 60_000,
    verifyOnTimeout: Boolean = true,
): NwcResult<PaymentResult>

suspend fun payKeysend(
    params: KeysendParams,
    timeoutMs: Long = 60_000,
): NwcResult<PaymentResult>

suspend fun makeInvoice(
    params: MakeInvoiceParams,
    timeoutMs: Long = 30_000,
): NwcResult<Transaction>

suspend fun lookupInvoice(
    params: LookupInvoiceParams,
    timeoutMs: Long = 30_000,
): NwcResult<Transaction>

suspend fun listTransactions(
    params: ListTransactionsParams = ListTransactionsParams(),
    timeoutMs: Long = 30_000,
): NwcResult<List<Transaction>>

suspend fun multiPayInvoice(
    items: List<MultiPayInvoiceItem>,
    timeoutMs: Long = 120_000,
): NwcResult<Map<String, MultiPayItemResult>>

suspend fun multiPayKeysend(
    items: List<MultiKeysendItem>,
    timeoutMs: Long = 120_000,
): NwcResult<Map<String, MultiPayItemResult>>

suspend fun checkPaymentStatus(
    paymentHash: String,
    timeoutMs: Long = 30_000,
): NwcResult<Transaction>

suspend fun refreshWalletInfo(): NwcResult<WalletInfo>
```

## `NwcConnectionUri`

```kotlin
data class NwcConnectionUri(
    val raw: String,
    val walletPubkey: PublicKey,
    val secret: ByteString,
    val relays: List<String>,
    val lud16: String? = null,
) {
    val clientPubkey: PublicKey
}
```

Helpers:

```kotlin
NwcConnectionUri.parse(uri: String): NwcConnectionUri?
NwcConnectionUri.parseDeepLink(uri: String): NwcConnectionUri?
NwcConnectionUri.isValid(uri: String): Boolean
```

`secret` is the 32-byte client secret. `clientPubkey` is derived lazily from
that secret.

## Amounts

`Amount` stores millisatoshis, the unit used by NWC:

```kotlin
val amount = Amount.fromSats(1_000)
println(amount.msats) // 1000000
println(amount.sats)  // 1000
println(amount.btc)
```

Constructors:

```kotlin
Amount(msats: Long)
Amount.fromSats(sats: Long)
Amount.fromMsats(msats: Long)
Amount.ZERO
```

`Amount` implements `Comparable<Amount>` and supports `+` and `-`.

## Parameters and Results

Payment parameters:

```kotlin
data class PayInvoiceParams(
    val invoice: String,
    val amount: Amount? = null,
)

data class KeysendParams(
    val pubkey: String,
    val amount: Amount,
    val preimage: String? = null,
    val tlvRecords: List<TlvRecord> = emptyList(),
)

data class TlvRecord(
    val type: Long,
    val value: String,
)
```

Invoice and transaction parameters:

```kotlin
data class MakeInvoiceParams(
    val amount: Amount,
    val description: String? = null,
    val descriptionHash: String? = null,
    val expirySeconds: Long? = null,
)

data class LookupInvoiceParams(
    val paymentHash: String? = null,
    val invoice: String? = null,
)

data class ListTransactionsParams(
    val from: Long? = null,
    val until: Long? = null,
    val limit: Int? = null,
    val offset: Int? = null,
    val unpaid: Boolean? = null,
    val type: TransactionType? = null,
)
```

Payment result:

```kotlin
data class PaymentResult(
    val preimage: String,
    val feesPaid: Amount? = null,
)
```

## Transactions

```kotlin
data class Transaction(
    val type: TransactionType,
    val state: TransactionState?,
    val paymentHash: String,
    val amount: Amount,
    val invoice: String? = null,
    val description: String? = null,
    val descriptionHash: String? = null,
    val preimage: String? = null,
    val feesPaid: Amount? = null,
    val createdAt: Long,
    val expiresAt: Long? = null,
    val settledAt: Long? = null,
)
```

Enums:

```kotlin
enum class TransactionType { INCOMING, OUTGOING }
enum class TransactionState { PENDING, SETTLED, EXPIRED, FAILED }
```

Timestamps are Unix seconds.

## Wallet Info

`WalletInfo` comes from the NWC info event:

```kotlin
data class WalletInfo(
    val capabilities: Set<NwcCapability>,
    val notifications: Set<NwcNotificationType>,
    val encryptions: Set<NwcEncryption>,
    val preferredEncryption: NwcEncryption,
    val encryptionDefaultedToNip04: Boolean = false,
)
```

Useful members:

```kotlin
walletInfo.supports(NwcCapability.PAY_INVOICE)
walletInfo.supportsNotifications
walletInfo.capabilityStrings
walletInfo.notificationStrings
walletInfo.encryptionStrings
```

`WalletDetails` comes from `get_info` and may include alias, node pubkey,
network, block height, and method lists.

## Capabilities

```kotlin
enum class NwcCapability {
    PAY_INVOICE,
    MULTI_PAY_INVOICE,
    PAY_KEYSEND,
    MULTI_PAY_KEYSEND,
    MAKE_INVOICE,
    LOOKUP_INVOICE,
    LIST_TRANSACTIONS,
    GET_BALANCE,
    GET_INFO,
    NOTIFICATIONS,
}
```

Notification types:

```kotlin
enum class NwcNotificationType {
    PAYMENT_RECEIVED,
    PAYMENT_SENT,
}
```

## Results and Errors

Wallet calls return:

```kotlin
sealed class NwcResult<out T> {
    data class Success<T>(val value: T) : NwcResult<T>()
    data class Failure(val error: NwcError) : NwcResult<Nothing>()
}
```

Helpers include:

```kotlin
result.isSuccess
result.isFailure
result.isTimeout
result.isPaymentPending
result.getOrNull()
result.errorOrNull()
result.getOrThrow()
result.getOrDefault(default)
result.getOrElse { error -> fallback }
result.map { value -> transformed }
result.flatMap { value -> nextResult }
result.mapError { error -> mappedError }
result.onSuccess { value -> }
result.onFailure { error -> }
```

Error types:

```kotlin
sealed class NwcError {
    data class WalletError(
        val code: NwcErrorCode,
        override val message: String,
    ) : NwcError()

    data class ProtocolError(
        override val message: String,
        val cause: Throwable? = null,
    ) : NwcError()

    data class ConnectionError(
        override val message: String,
        val cause: Throwable? = null,
    ) : NwcError()

    data class Timeout(
        override val message: String = "Operation timed out",
        val durationMs: Long? = null,
    ) : NwcError()

    data class PaymentPending(
        override val message: String = "Payment sent but not yet confirmed",
        val paymentHash: String? = null,
    ) : NwcError()

    data class Cancelled(
        override val message: String = "Operation cancelled",
    ) : NwcError()

    data class CryptoError(
        override val message: String,
        val cause: Throwable? = null,
    ) : NwcError()
}
```

NIP-47 wallet error codes:

```kotlin
enum class NwcErrorCode {
    RATE_LIMITED,
    NOT_IMPLEMENTED,
    INSUFFICIENT_BALANCE,
    QUOTA_EXCEEDED,
    RESTRICTED,
    UNAUTHORIZED,
    INTERNAL,
    PAYMENT_FAILED,
    NOT_FOUND,
    UNSUPPORTED_ENCRYPTION,
    OTHER,
    UNKNOWN,
}
```

## Deep-Link Types

```kotlin
sealed class NwcDeepLink {
    data class ConnectionUri(val uri: NwcConnectionUri) : NwcDeepLink()
    data class Callback(val value: String) : NwcDeepLink()
}
```

Helpers:

```kotlin
NwcDeepLink.parse(uri: String): NwcDeepLink?
NwcDeepLink.isNwcDeepLink(uri: String): Boolean
```

The parser recognizes direct `nostr+walletconnect` URIs and callback URIs that
start with `nostrnwc://`. For custom callback schemes, extract the decoded
`value` query parameter with platform APIs and parse that value as an NWC URI.

Build wallet connection requests:

```kotlin
val requestUri = NwcConnectRequest.Builder()
    .appName("Example App")
    .appIcon("https://example.com/icon.png")
    .callback("nostrnwc://connect")
    .walletApp("walletname")
    .build()
    .toUri()
```

## Platform Notes

- Android uses the Ktor OkHttp engine.
- JVM uses the Ktor OkHttp engine.
- iOS uses the Ktor Darwin engine.
- The iOS framework is static and named `NwcKmp`.
- Nostr public keys and NWC encryption types from `nostr-kmp` are part of the
  public API.
