# Client Lifecycle

`NwcClient` owns the relay session, response subscriptions, notification
subscriptions, request correlation, and wallet info state for one NWC
connection URI.

## Creating a Client

Use one of the companion factories:

```kotlin
val client = NwcClient(uri, scope)
```

```kotlin
val client = NwcClient.fromUri(connectionUriString, scope)
    ?: error("Invalid NWC URI")
```

```kotlin
val client = NwcClient.fromDeepLink(deepLinkUri, scope)
    ?: error("Invalid NWC deep link")
```

Each factory starts connection setup immediately by calling `connect()`.

## Connection Flow

Connection setup does the following:

1. Connects to the first relay in `NwcConnectionUri.relays`.
2. Fetches the wallet info event, kind `13194`.
3. Subscribes to NWC response events addressed to the client's public key.
4. Subscribes to wallet notifications when the wallet advertises support.
5. Moves `state` to `NwcClientState.Ready`.

The current implementation uses the first relay from the URI. If a connection
URI contains multiple relays, keep them for compatibility with wallet formats,
but expect client traffic to use `relays.first()`.

## State

The client exposes:

```kotlin
val state: StateFlow<NwcClientState>
```

Possible states:

- `Disconnected`: no active relay session.
- `Connecting`: relay connection and subscriptions are being established.
- `Ready`: requests can be sent.
- `Closed`: `close()` was called.
- `Failed`: connection setup failed with a typed `NwcError`.

Observe state from UI code:

```kotlin
scope.launch {
    client.state.collect { state ->
        when (state) {
            NwcClientState.Disconnected -> println("Disconnected")
            NwcClientState.Connecting -> println("Connecting")
            NwcClientState.Ready -> println("Ready")
            NwcClientState.Closed -> println("Closed")
            is NwcClientState.Failed -> println(state.error.message)
        }
    }
}
```

## Readiness

Use `awaitReady` when your workflow needs an explicit connection gate:

```kotlin
val ready = client.awaitReady(timeoutMs = 10_000)
if (!ready) {
    // Show setup error or retry.
}
```

Wallet operations also call the client's internal connection guard. If the
session is disconnected, the operation attempts to reconnect within its timeout
budget before publishing the request.

## Reconnection and In-Flight Requests

NWC request and response events are ephemeral. If the relay connection drops
while a request is in flight, the response may not be replayed after reconnect.
The client therefore fails pending requests instead of assuming they can be
recovered.

For outgoing payments, `payInvoice` defaults to `verifyOnTimeout = true`. If the
payment request times out, the client performs `lookup_invoice` for the invoice:

- A settled transaction becomes a successful `PaymentResult`.
- A pending transaction becomes `NwcError.PaymentPending`.
- A failed, expired, missing, or unverifiable transaction becomes a timeout.

For app restart recovery, persist the payment hash when you have one and call:

```kotlin
val status = client.checkPaymentStatus(paymentHash)
```

## Wallet Info

The client exposes:

```kotlin
val walletInfo: StateFlow<WalletInfo>
```

Before the wallet info event is fetched, the StateFlow contains
`WalletInfo.default()`. That default has no capabilities or notifications and
uses NIP-04 for backwards compatibility.

After connection, `walletInfo` reflects the wallet info event:

```kotlin
val info = client.walletInfo.value
if (info.supports(NwcCapability.PAY_INVOICE)) {
    println("Payments are supported")
}
```

Refresh wallet info manually:

```kotlin
when (val result = client.refreshWalletInfo()) {
    is NwcResult.Success -> println(result.value.capabilityStrings)
    is NwcResult.Failure -> println(result.error.message)
}
```

## Encryption

The client supports NIP-04 and NIP-44 v2 through `nostr-kmp`.

Selection is driven by wallet info:

- Prefer NIP-44 v2 when advertised.
- Use NIP-04 when advertised and NIP-44 is not available.
- Default to NIP-04 when the wallet does not advertise encryption support.

Inspect the current choice:

```kotlin
val encryption = client.encryption
```

If `walletInfo.value.encryptionDefaultedToNip04` is true, the wallet did not
publish an encryption tag in its info event.

## Notifications

Notifications are exposed as a `SharedFlow`:

```kotlin
val notifications: SharedFlow<WalletNotification>
```

Collect them while the client is alive:

```kotlin
scope.launch {
    client.notifications.collect { notification ->
        when (notification) {
            is WalletNotification.PaymentReceived -> {
                println("Received ${notification.transaction.amount}")
            }
            is WalletNotification.PaymentSent -> {
                println("Sent ${notification.transaction.amount}")
            }
        }
    }
}
```

Notification collection only receives events emitted while subscribed. It has no
replay cache. Use `listTransactions` or `lookupInvoice` when you need durable
history.

## Closing

Call `close()` when the client is no longer needed:

```kotlin
client.close()
```

Closing cancels relay subscriptions, closes the session, stops collector jobs,
and fails pending requests with `NwcError.Cancelled`. Prefer creating a new
client for a new long-lived lifecycle.

