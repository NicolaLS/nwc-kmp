# Getting Started

This guide covers the shortest path from a Nostr Wallet Connect URI to a
working `NwcClient`.

## Add the Dependency

Snapshot builds are served from Maven Central's snapshot repository:

```kotlin
repositories {
    google()
    mavenCentral()
    maven {
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        mavenContent { snapshotsOnly() }
    }
}
```

Add the SDK to your KMP module:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.nicolals:nwc-kmp:0.3.1-SNAPSHOT")
        }
    }
}
```

The SDK brings in the Nostr transport, codec, crypto, NIP-04, NIP-44, and
NIP-47 modules it needs.

## Parse a Connection URI

NWC connection strings use this shape:

```text
nostr+walletconnect://<wallet-pubkey>?relay=<relay-url>&secret=<client-secret>&lud16=<optional>
```

Parse one directly with `NwcConnectionUri`:

```kotlin
import io.github.nicolals.nwc.NwcConnectionUri

val uri = NwcConnectionUri.parse(connectionUriString)
    ?: error("Invalid NWC URI")

println(uri.walletPubkey)
println(uri.clientPubkey)
println(uri.relays)
println(uri.lud16)
```

The parser requires:

- A wallet public key.
- A 32-byte hex client secret.
- At least one `relay` query parameter.

Relay URLs are URL-decoded during parsing. Network validation happens later
when the client connects.

## Create a Client

Create the client with a coroutine scope owned by your application lifecycle:

```kotlin
import io.github.nicolals.nwc.NwcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
val client = NwcClient(uri, scope)
```

The factory starts connecting immediately. Operations also make sure a
connection exists before sending a request, so this is valid:

```kotlin
val balanceResult = client.getBalance()
```

For setup screens, waiting for readiness gives you a clearer UI state:

```kotlin
if (!client.awaitReady(timeoutMs = 10_000)) {
    error("Could not connect to the NWC relay")
}
```

`awaitReady` returns `true` only when the client reaches `NwcClientState.Ready`.
It returns `false` on timeout or connection failure.

## Use `fromUri` for User Input

When the URI comes from a QR scan, paste field, or deep link, `fromUri` avoids a
separate parse step:

```kotlin
val client = NwcClient.fromUri(connectionUriString, scope)
    ?: error("Invalid NWC URI")
```

For links that may be either direct NWC URIs or `nostrnwc://...` wallet
callback URIs, use:

```kotlin
val client = NwcClient.fromDeepLink(deepLinkUri, scope)
    ?: error("Invalid NWC deep link")
```

If your app uses a custom callback scheme, extract the callback's decoded
`value` query parameter with your platform URL parser, then pass that value to
`NwcConnectionUri.parse` or `NwcClient.fromUri`. See [Deep Links](deep-links.md)
for callback setup.

## Handle Results

All wallet operations return `NwcResult<T>`:

```kotlin
import io.github.nicolals.nwc.NwcResult

when (val result = client.getBalance()) {
    is NwcResult.Success -> {
        val balance = result.value
        println("Balance: $balance")
    }
    is NwcResult.Failure -> {
        println("Failed: ${result.error.message}")
    }
}
```

You can also use helpers:

```kotlin
val balanceOrNull = client.getBalance().getOrNull()
val balanceOrZero = client.getBalance().getOrElse { Amount.ZERO }
```

`getOrThrow()` throws `NwcException` when the result is a failure.

## Close the Client

Close the client when the owning screen, view model, service, or app lifecycle
ends:

```kotlin
client.close()
scope.cancel()
```

`close()` stops relay subscriptions, closes the current session, and fails
pending requests with `NwcError.Cancelled`.

## Cache Wallet Info

The client fetches the wallet info event when connecting. If you persisted a
previous `WalletInfo`, you can pass it at construction time so the UI has
capabilities before the relay fetch completes:

```kotlin
val client = NwcClient(
    uri = uri,
    scope = scope,
    cachedWalletInfo = storedWalletInfo,
)
```

The `walletInfo` StateFlow updates after fresh info is fetched from the relay.

## Custom HTTP Client

Pass a Ktor `HttpClient` when you need custom engines, timeouts, logging, or
platform configuration:

```kotlin
import io.ktor.client.HttpClient

val httpClient = HttpClient()
val client = NwcClient(
    uri = uri,
    scope = scope,
    httpClient = httpClient,
)
```

Manage the `HttpClient` lifecycle according to your app's ownership model.
