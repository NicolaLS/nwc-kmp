# Example App Integration: LASR

[NicolaLS/lasr](https://github.com/NicolaLS/lasr) is a Compose Multiplatform app
that uses `nwc-kmp` for Nostr Wallet Connect payments. Its app module shows a
useful production shape for NWC integration:

- Keep `NwcClient` behind app repositories and use cases.
- Discover wallet metadata before saving a connection.
- Persist a small wallet metadata snapshot and pass it back as
  `cachedWalletInfo`.
- Reuse one client per wallet URI while the app is in the foreground.
- Close clients when the app goes to the background.
- Map `NwcResult` and `NwcError` into app-level payment states.

## Store the Connection as App Data

An app usually should not pass raw SDK types through every screen. LASR stores a
domain model with the sensitive URI, display fields, and a metadata snapshot:

```kotlin
data class WalletConnection(
    val walletPublicKey: String,
    val alias: String?,
    val uri: String,
    val relayUrl: String?,
    val lud16: String?,
    val metadata: WalletMetadataSnapshot? = null,
)

data class WalletMetadataSnapshot(
    val methods: Set<String> = emptySet(),
    val encryptionSchemes: Set<String> = emptySet(),
    val negotiatedEncryption: String? = null,
    val encryptionDefaultedToNip04: Boolean = false,
    val notifications: Set<String> = emptySet(),
    val network: String? = null,
    val color: String? = null,
)
```

Store this in secure app storage because `uri` contains the NWC client secret.

## Discover Before Saving

LASR calls `NwcClient.discover` from an onboarding or connect-wallet flow. This
validates the URI, fetches the wallet info event, tries `get_info`, and closes
the temporary client.

```kotlin
import io.github.nicolals.nwc.NwcClient
import io.github.nicolals.nwc.NwcResult
import io.github.nicolals.nwc.WalletDiscovery as SdkWalletDiscovery
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WalletDiscoveryRepository(
    private val httpClient: HttpClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun discover(uri: String): WalletDiscovery = withContext(dispatcher) {
        val trimmed = uri.trim()
        require(trimmed.isNotEmpty()) { "NWC URI is required" }

        when (
            val result = NwcClient.discover(
                uri = trimmed,
                httpClient = httpClient,
                timeoutMs = 10_000,
            )
        ) {
            is NwcResult.Success -> result.value.toDomain()
            is NwcResult.Failure -> error(result.error.message)
        }
    }

    private fun SdkWalletDiscovery.toDomain(): WalletDiscovery = WalletDiscovery(
        uri = uri.raw,
        walletPublicKey = uri.walletPubkey.hex,
        relayUrl = uri.relays.firstOrNull(),
        lud16 = uri.lud16,
        aliasSuggestion = details?.alias,
        methods = walletInfo.capabilityStrings,
        encryptionSchemes = walletInfo.encryptionStrings,
        negotiatedEncryption = walletInfo.preferredEncryption.tag,
        encryptionDefaultedToNip04 = walletInfo.encryptionDefaultedToNip04,
        notifications = walletInfo.notificationStrings,
        network = details?.network,
        color = details?.color,
    )
}

data class WalletDiscovery(
    val uri: String,
    val walletPublicKey: String,
    val relayUrl: String?,
    val lud16: String?,
    val aliasSuggestion: String?,
    val methods: Set<String>,
    val encryptionSchemes: Set<String>,
    val negotiatedEncryption: String?,
    val encryptionDefaultedToNip04: Boolean,
    val notifications: Set<String>,
    val network: String?,
    val color: String?,
)

fun WalletDiscovery.toMetadataSnapshot(): WalletMetadataSnapshot = WalletMetadataSnapshot(
    methods = methods,
    encryptionSchemes = encryptionSchemes,
    negotiatedEncryption = negotiatedEncryption,
    encryptionDefaultedToNip04 = encryptionDefaultedToNip04,
    notifications = notifications,
    network = network,
    color = color,
)
```

This lets a UI show the wallet alias, network, capabilities, and encryption
choice before the user confirms the connection.

## Save a Validated URI

After discovery succeeds and the user confirms, parse the URI again and store
only normalized fields:

```kotlin
import io.github.nicolals.nwc.NwcConnectionUri

class SetWalletConnectionUseCase(
    private val repository: WalletSettingsRepository,
) {
    suspend operator fun invoke(
        uri: String,
        alias: String?,
        metadata: WalletMetadataSnapshot?,
    ): WalletConnection {
        val parsed = NwcConnectionUri.parse(uri.trim())
            ?: error("Invalid NWC URI")

        val connection = WalletConnection(
            uri = parsed.raw,
            walletPublicKey = parsed.walletPubkey.hex,
            relayUrl = parsed.relays.firstOrNull(),
            lud16 = parsed.lud16,
            alias = alias?.takeIf { it.isNotBlank() }?.trim(),
            metadata = metadata,
        )

        repository.saveWalletConnection(connection)
        return connection
    }
}

interface WalletSettingsRepository {
    suspend fun saveWalletConnection(connection: WalletConnection)
    suspend fun getWalletConnection(): WalletConnection?
}
```

## Rehydrate Cached Wallet Info

Passing cached wallet info lets the app make a good encryption choice
immediately while a fresh info event is fetched in the background.

```kotlin
import io.github.nicolals.nostr.nip47.model.NwcEncryption
import io.github.nicolals.nwc.NwcCapability
import io.github.nicolals.nwc.NwcNotificationType
import io.github.nicolals.nwc.WalletInfo

fun WalletMetadataSnapshot.toWalletInfo(): WalletInfo {
    val capabilities = methods.mapNotNull { NwcCapability.fromValue(it) }.toSet()
    val notificationTypes = notifications.mapNotNull {
        NwcNotificationType.fromValue(it)
    }.toSet()
    val encryptions = encryptionSchemes.mapNotNull { NwcEncryption.fromTag(it) }.toSet()

    val preferredEncryption = when {
        negotiatedEncryption != null ->
            NwcEncryption.fromTag(negotiatedEncryption) ?: NwcEncryption.NIP04

        NwcEncryption.NIP44_V2 in encryptions -> NwcEncryption.NIP44_V2
        NwcEncryption.NIP04 in encryptions -> NwcEncryption.NIP04
        else -> NwcEncryption.NIP04
    }

    return WalletInfo(
        capabilities = capabilities,
        notifications = notificationTypes,
        encryptions = encryptions.ifEmpty { setOf(NwcEncryption.NIP04) },
        preferredEncryption = preferredEncryption,
        encryptionDefaultedToNip04 = encryptionDefaultedToNip04,
    )
}
```

## Create Clients Through a Factory

LASR centralizes client creation so every caller gets the same shared
`HttpClient`, app coroutine scope, and cached metadata handling:

```kotlin
import io.github.nicolals.nwc.NwcClient
import io.github.nicolals.nwc.NwcConnectionUri
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope

fun interface NwcClientFactory {
    fun create(connection: WalletConnection): NwcClient
}

class RealNwcClientFactory(
    private val httpClient: HttpClient,
    private val scope: CoroutineScope,
) : NwcClientFactory {
    override fun create(connection: WalletConnection): NwcClient {
        val uri = NwcConnectionUri.parse(connection.uri)
            ?: error("Invalid NWC URI")

        return NwcClient(
            uri = uri,
            scope = scope,
            httpClient = httpClient,
            cachedWalletInfo = connection.metadata?.toWalletInfo(),
        )
    }
}
```

`NwcClient` auto-connects on creation. Callers can use operations directly; each
operation waits for connection or attempts reconnect within its timeout budget.

## Reuse Clients While Foregrounded

Payment apps often issue several operations close together. Reusing a client by
wallet URI avoids connection thrashing and keeps response subscriptions alive.

```kotlin
import io.github.nicolals.nwc.NwcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NwcConnectionManager(
    private val appLifecycle: AppLifecycleObserver,
    private val walletSettings: WalletSettingsRepository,
    private val clientFactory: NwcClientFactory,
    scope: CoroutineScope,
) {
    private val clients = mutableMapOf<String, NwcClient>()
    private val mutex = Mutex()

    init {
        scope.launch {
            combine(
                appLifecycle.isInForeground,
                walletSettings.walletConnection,
            ) { isForeground, activeConnection ->
                isForeground to activeConnection
            }.collectLatest { (isForeground, activeConnection) ->
                if (isForeground && activeConnection != null) {
                    runCatching { getClient(activeConnection) }
                } else if (!isForeground) {
                    disconnectAll()
                }
            }
        }
    }

    suspend fun getClient(connection: WalletConnection? = null): NwcClient {
        val selected = connection ?: walletSettings.getWalletConnection()
            ?: error("Missing wallet connection")

        return mutex.withLock {
            clients.getOrPut(selected.uri) {
                clientFactory.create(selected)
            }
        }
    }

    suspend fun disconnectAll() {
        val clientsToClose = mutex.withLock {
            val copy = clients.values.toList()
            clients.clear()
            copy
        }
        clientsToClose.forEach { client ->
            runCatching { client.close() }
        }
    }
}
```

The app lifecycle abstraction can be any platform-specific `StateFlow<Boolean>`
that tells shared code whether the app is foregrounded.

```kotlin
interface AppLifecycleObserver {
    val isInForeground: kotlinx.coroutines.flow.StateFlow<Boolean>
}

interface WalletSettingsRepository {
    val walletConnection: kotlinx.coroutines.flow.Flow<WalletConnection?>
    suspend fun getWalletConnection(): WalletConnection?
    suspend fun saveWalletConnection(connection: WalletConnection)
}
```

## Wrap Payments in an App Repository

LASR maps SDK results into app models and errors at the data boundary:

```kotlin
import io.github.nicolals.nwc.Amount
import io.github.nicolals.nwc.LookupInvoiceParams
import io.github.nicolals.nwc.NwcError
import io.github.nicolals.nwc.NwcResult
import io.github.nicolals.nwc.TransactionState

class NwcWalletRepository(
    private val connectionManager: NwcConnectionManager,
    private val networkConnectivity: NetworkConnectivity,
) {
    suspend fun payInvoice(
        invoice: String,
        amountMsats: Long?,
    ): PaidInvoice {
        require(invoice.isNotBlank()) { "Invoice must not be blank" }
        require(amountMsats == null || amountMsats > 0) {
            "Amount must be greater than zero"
        }
        check(networkConnectivity.isNetworkAvailable()) {
            "Network unavailable"
        }

        val result = connectionManager.getClient().payInvoice(
            invoice = invoice,
            amount = amountMsats?.let { Amount.fromMsats(it) },
            timeoutMs = 20_000,
            verifyOnTimeout = true,
        )

        return when (result) {
            is NwcResult.Success -> PaidInvoice(
                preimage = result.value.preimage,
                feesPaidMsats = result.value.feesPaid?.msats,
            )

            is NwcResult.Failure -> throw result.error.toAppException()
        }
    }

    suspend fun lookupPayment(paymentHash: String): PaymentLookupResult {
        val result = connectionManager.getClient().lookupInvoice(
            params = LookupInvoiceParams(paymentHash = paymentHash),
            timeoutMs = 10_000,
        )

        return when (result) {
            is NwcResult.Success -> when (result.value.state) {
                TransactionState.SETTLED -> PaymentLookupResult.Settled(
                    PaidInvoice(
                        preimage = result.value.preimage,
                        feesPaidMsats = result.value.feesPaid?.msats,
                    )
                )
                TransactionState.PENDING -> PaymentLookupResult.Pending
                TransactionState.FAILED,
                TransactionState.EXPIRED,
                null -> PaymentLookupResult.Failed
            }

            is NwcResult.Failure -> when (val error = result.error) {
                is NwcError.WalletError ->
                    if (error.code.code == "NOT_FOUND") {
                        PaymentLookupResult.NotFound
                    } else {
                        PaymentLookupResult.LookupError(error.message)
                    }

                else -> PaymentLookupResult.LookupError(error.message)
            }
        }
    }
}

data class PaidInvoice(
    val preimage: String?,
    val feesPaidMsats: Long?,
)

sealed interface PaymentLookupResult {
    data class Settled(val payment: PaidInvoice) : PaymentLookupResult
    data object Pending : PaymentLookupResult
    data object Failed : PaymentLookupResult
    data object NotFound : PaymentLookupResult
    data class LookupError(val message: String) : PaymentLookupResult
}

interface NetworkConnectivity {
    fun isNetworkAvailable(): Boolean
}

fun NwcError.toAppException(): RuntimeException =
    RuntimeException(message)
```

In a real app, map `NwcError.PaymentPending` to a specific "payment
unconfirmed" UI state so the user can retry status checks without paying again.

## Route Direct NWC Links

LASR accepts direct `nostr+walletconnect` links and opens its connect-wallet
dialog:

```kotlin
private const val NWC_SCHEME = "nostr+walletconnect"

fun handleDeepLink(uri: String, openConnectWallet: (String) -> Unit) {
    val normalized = uri.trim()
    val scheme = normalized.substringBefore(":", missingDelimiterValue = "")

    if (scheme.equals(NWC_SCHEME, ignoreCase = true)) {
        val normalizedUri = if (
            normalized.startsWith("$NWC_SCHEME://", ignoreCase = true)
        ) {
            normalized
        } else {
            val afterScheme = normalized
                .substringAfter(":", missingDelimiterValue = "")
                .trimStart('/')
            "$NWC_SCHEME://$afterScheme"
        }

        openConnectWallet(normalizedUri)
    }
}
```

For `nostrnwc://...` callback links, use `NwcClient.fromDeepLink` or
`NwcDeepLink.parse`. For custom callback schemes, extract the decoded `value`
query parameter with platform APIs and pass it to `NwcClient.fromUri`.

## Dependency Injection Shape

LASR wires the NWC layer as singletons:

```kotlin
single { createNwcHttpClient() }
single { createAppLifecycleObserver() }

single<NwcClientFactory> {
    RealNwcClientFactory(
        httpClient = get(),
        scope = get(),
    )
}

single(createdAtStart = true) {
    NwcConnectionManager(
        appLifecycle = get(),
        walletSettings = get(),
        clientFactory = get(),
        scope = get(),
    )
}

single {
    NwcWalletRepository(
        connectionManager = get(),
        networkConnectivity = get(),
    )
}
```

The specific DI library does not matter. The important ownership rule is that
the `CoroutineScope`, `HttpClient`, and cached `NwcClient` instances have clear
app-level lifetimes.

## What to Copy

- Use `NwcClient.discover` before saving a URI.
- Persist wallet capabilities and encryption tags as strings.
- Convert persisted metadata into `WalletInfo` and pass it as
  `cachedWalletInfo`.
- Cache clients by URI while the app is foregrounded.
- Close clients on background to release WebSocket resources.
- Keep `NwcResult` handling inside a data layer and expose app-specific states
  to UI code.
