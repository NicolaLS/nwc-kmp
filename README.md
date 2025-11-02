# Nostr Wallet Connect KMP

Kotlin Multiplatform client for [Nostr Wallet Connect (NIP-47)](https://github.com/nostr-protocol/nips/blob/master/47.md). The library exposes session management, typed requests, wallet discovery, and structured error handling for JVM, Android, iOS, and other Kotlin targets.

## Features

- Immutable credential and session APIs (`NwcUri`, `NwcSession`, `NwcSessionManager`).
- `NwcClientContract` interface with production implementation and test fakes.
- Wallet discovery helpers returning typed capabilities and metadata.
- Structured `NwcFailure` taxonomy with detailed diagnostics for connection issues.
- Streaming connection state and wallet metadata updates via `StateFlow`.

## Modules

- `nwc-kmp`: multiplatform runtime delivering the Nostr Wallet Connect client.

## Adding the Dependency

```kotlin
dependencies {
    implementation("io.github.nicolals:nwc-kmp:0.1.0-SNAPSHOT")
}
```


## Quickstart: Wallet Discovery

```kotlin
import io.github.nostr.nwc.DEFAULT_REQUEST_TIMEOUT_MS
import io.github.nostr.nwc.discoverWallet
import io.github.nostr.nwc.model.NwcCapability
import io.github.nostr.nwc.model.NwcResult

suspend fun loadDescriptor(connectionUri: String) {
    when (val result = discoverWallet(
        uri = connectionUri,
        requestTimeoutMillis = DEFAULT_REQUEST_TIMEOUT_MS
    )) {
        is NwcResult.Success -> {
            val descriptor = result.value
            if (NwcCapability.PayInvoice in descriptor.capabilities) {
                println("Wallet ${'$'}{descriptor.alias} supports paying invoices on ${'$'}{descriptor.network}")
            }
            descriptor.notifications.forEach { println("Supports notification: ${'$'}it") }
        }
        is NwcResult.Failure -> println("Discovery failed: ${'$'}{result.failure}")
    }
}
```

For long-lived integrations, reuse a shared `NwcSession`:

```kotlin
val session = NwcSession.create(connectionUri)
val descriptorResult = discoverWallet(session)
// Later reuse the same session when creating NwcClient instances
```

The `NwcWalletDescriptor` combines the latest wallet metadata and `get_info` response with typed capability and notification sets so you can reason about features without string matching.

## End-to-End Flow

```kotlin
import io.github.nostr.nwc.NwcClient
import io.github.nostr.nwc.NwcClientContract
import io.github.nostr.nwc.model.NwcResult
import io.github.nostr.nwc.model.PayInvoiceParams
import kotlinx.coroutines.CoroutineScope

class WalletRepository(private val clientFactory: suspend (CoroutineScope) -> NwcClientContract) {
    suspend fun payInvoice(scope: CoroutineScope, invoice: String) {
        val client = clientFactory(scope)
        try {
            when (val result = client.payInvoice(PayInvoiceParams(invoice))) {
                is NwcResult.Success -> println("Paid invoice: ${result.value.preimage}")
                is NwcResult.Failure -> println("Payment failed: ${result.failure}")
            }
        } finally {
            client.close()
        }
    }
}

suspend fun realClientFactory(scope: CoroutineScope): NwcClientContract =
    NwcClient.create("nostr+walletconnect://<wallet-pubkey>?relay=wss://<relay>&secret=<hex>", scope)
```

Consumers depend only on `NwcClientContract`, making it easy to inject either the real client or the new fakes in tests.

## Testing Utilities

- `FakeNwcClient` implements `NwcClientContract` and records each request, letting consumers drive their own success or failure scenarios without spinning up relays.
- `ScriptedWalletHarness` responds to `pay_invoice`, `make_invoice`, and `list_transactions` commands using a deterministic ledger and optional scripts so integration tests can exercise the full flow.

Both utilities live under `io.github.nostr.nwc.testing` and are available on all common Kotlin Multiplatform targets.

## Error Handling

All public APIs return `NwcResult`, exposing rich failure data without throwing. `NwcFailure.Network` now surfaces the upstream `ConnectionFailureReason`, websocket close code/reason, and the original cause string published by the runtime so callers can distinguish connection factory errors from relay handshake issues without parsing log messages.

## Publishing

### Publish to Maven Local

1. Ensure the upstream `nostr-kmp` snapshot you depend on is already available in `~/.m2` (see its project docs for publishing instructions).
2. From this repository root run:
   ```bash
   ./gradlew :nwc-kmp:publishToMavenLocal
   ```
   This produces `io.github.nicolals:nwc-kmp:<version>` in `~/.m2/repository`.
3. Downstream projects can then depend on `implementation("io.github.nicolals:nwc-kmp:<version>")` while developing locally.

### Bumping the Version

- Edit `gradle.properties` and update the `VERSION_NAME` property. The file also carries the `GROUP`, `POM_ARTIFACT_ID`, and other publishing metadata used during publication.
- Follow semantic versioning. Use `-SNAPSHOT` while iterating; drop the suffix when cutting a real release.
- After adjusting the version, re-run `./gradlew :nwc-kmp:publishToMavenLocal` so Maven consumers pick up the new artifact.
