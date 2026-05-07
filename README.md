# nwc-kmp

`nwc-kmp` is a Kotlin Multiplatform client for Nostr Wallet Connect (NWC,
NIP-47). It provides a typed API for connecting to an NWC wallet service,
discovering wallet capabilities, paying invoices, creating invoices, listing
transactions, and receiving wallet notifications.

The SDK is built on top of `nostr-kmp` and supports Android, JVM, and iOS
targets.

## Features

- Parse `nostr+walletconnect` URIs and `nostrnwc` callback links.
- Connect to NWC wallet services over Nostr relays with Ktor WebSockets.
- Use NIP-44 v2 when the wallet advertises support, with NIP-04 fallback.
- Fetch wallet info events and typed `get_info` details.
- Call common NWC methods: `get_balance`, `pay_invoice`, `make_invoice`,
  `lookup_invoice`, `list_transactions`, `pay_keysend`, and multi-pay methods.
- Collect `payment_received` and `payment_sent` notifications when supported.
- Handle failures through `NwcResult` and typed `NwcError` values.

## Installation

Add the snapshot repository while using snapshot versions:

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

Add the dependency from your Kotlin Multiplatform module:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.nicolals:nwc-kmp:0.3.1-SNAPSHOT")
        }
    }
}
```

Release versions are published to Maven Central and do not require the snapshot
repository.

## Quick Start

```kotlin
import io.github.nicolals.nwc.NwcClient
import io.github.nicolals.nwc.NwcResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

suspend fun printBalance(connectionUri: String) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val client = NwcClient.fromUri(connectionUri, scope)
        ?: error("Invalid NWC connection URI")

    try {
        if (!client.awaitReady(timeoutMs = 10_000)) {
            error("NWC client did not become ready")
        }

        when (val result = client.getBalance()) {
            is NwcResult.Success -> {
                println("Balance: ${result.value}")
            }
            is NwcResult.Failure -> {
                println("Balance failed: ${result.error.message}")
            }
        }
    } finally {
        client.close()
        scope.cancel()
    }
}
```

`NwcClient.fromUri` and `NwcClient(uri, scope)` start connecting immediately.
You can still call `connect()` explicitly; it is idempotent unless
`forceReconnect = true`.

## Documentation

- [Getting Started](docs/getting-started.md): installation, URI parsing,
  lifecycle, and first operations.
- [Client Lifecycle](docs/client.md): state, connection behavior, wallet info,
  notifications, and timeouts.
- [Recipes](docs/recipes.md): examples for each supported wallet operation.
- [Deep Links](docs/deep-links.md): app-to-wallet connection requests and
  callback parsing.
- [Example App Integration](docs/example-app.md): a Compose Multiplatform app
  structure adapted from [NicolaLS/lasr](https://github.com/NicolaLS/lasr).
- [API Reference](docs/reference.md): public models, errors, operations, and
  compatibility notes.
- [Publishing](docs/publishing.md): repository setup and release workflow.

## Supported Targets

- Android, min SDK 24.
- JVM, toolchain 21.
- iOS X64, Arm64, and Simulator Arm64.

The iOS framework is static and uses `NwcKmp` as the framework base name.

## Development

Use the Gradle wrapper from the repository root:

```sh
./gradlew build
./gradlew check
./gradlew :nwc-kmp:jvmTest
./gradlew publishToMavenLocal
```

The source lives in `nwc-kmp/src/commonMain/kotlin/io/github/nicolals/nwc`.
Common tests live in `nwc-kmp/src/commonTest/kotlin`.

## Security Notes

An NWC connection URI contains the client secret used to sign requests and
decrypt wallet responses. Treat the full URI like a credential:

- Do not log it.
- Do not send it to analytics.
- Store it only in secure app storage.
- Prefer showing wallet capabilities to the user before enabling payments.
