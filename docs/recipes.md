# Recipes

These examples assume you already have a ready client:

```kotlin
val client = NwcClient.fromUri(connectionUriString, scope)
    ?: error("Invalid NWC URI")

check(client.awaitReady(timeoutMs = 10_000)) {
    "NWC client did not become ready"
}
```

## Discover a Wallet Before Saving It

Use discovery during an onboarding flow to validate the URI and show the user
which capabilities the wallet grants.

```kotlin
import io.github.nicolals.nwc.NwcClient
import io.github.nicolals.nwc.NwcCapability
import io.github.nicolals.nwc.NwcResult

when (val result = NwcClient.discover(connectionUriString, timeoutMs = 10_000)) {
    is NwcResult.Success -> {
        val discovery = result.value
        println("Wallet: ${discovery.alias ?: discovery.uri.walletPubkey}")
        println("Network: ${discovery.network ?: "unknown"}")
        println("Can pay: ${NwcCapability.PAY_INVOICE in discovery.capabilities}")
    }
    is NwcResult.Failure -> {
        println("Discovery failed: ${result.error.message}")
    }
}
```

`discover` opens a temporary client and closes it automatically.

## Get Balance

```kotlin
when (val result = client.getBalance()) {
    is NwcResult.Success -> {
        println("Balance: ${result.value.sats} sats")
    }
    is NwcResult.Failure -> {
        println("Could not read balance: ${result.error.message}")
    }
}
```

## Get Wallet Details

`walletInfo` comes from the public info event. `getInfo()` asks the wallet for
additional details such as alias, network, block height, and supported methods
for this connection.

```kotlin
when (val result = client.getInfo()) {
    is NwcResult.Success -> {
        val details = result.value
        println(details.alias ?: "Unnamed wallet")
        println(details.network ?: "unknown network")
        println(details.methods)
    }
    is NwcResult.Failure -> {
        println("get_info failed: ${result.error.message}")
    }
}
```

## Pay a BOLT-11 Invoice

```kotlin
when (val result = client.payInvoice(invoice = bolt11)) {
    is NwcResult.Success -> {
        println("Paid with preimage ${result.value.preimage}")
        println("Fees: ${result.value.feesPaid ?: Amount.ZERO}")
    }
    is NwcResult.Failure -> {
        println("Payment failed: ${result.error.message}")
    }
}
```

For zero-amount invoices, pass an amount:

```kotlin
val result = client.payInvoice(
    invoice = bolt11,
    amount = Amount.fromSats(2_100),
)
```

`payInvoice` defaults to `verifyOnTimeout = true`. When timeout verification
shows the payment is still pending, the error is `NwcError.PaymentPending`:

```kotlin
when (val result = client.payInvoice(invoice = bolt11)) {
    is NwcResult.Success -> println(result.value.preimage)
    is NwcResult.Failure -> when (val error = result.error) {
        is NwcError.PaymentPending -> {
            println("Payment pending: ${error.paymentHash}")
        }
        else -> {
            println(error.message)
        }
    }
}
```

## Check Payment Status

Use this after receiving a pending payment error or after app restart recovery:

```kotlin
when (val result = client.checkPaymentStatus(paymentHash)) {
    is NwcResult.Success -> {
        val transaction = result.value
        println("Payment state: ${transaction.state}")
    }
    is NwcResult.Failure -> {
        println("Status check failed: ${result.error.message}")
    }
}
```

## Create an Invoice

```kotlin
val params = MakeInvoiceParams(
    amount = Amount.fromSats(5_000),
    description = "Coffee",
    expirySeconds = 3_600,
)

when (val result = client.makeInvoice(params)) {
    is NwcResult.Success -> {
        val transaction = result.value
        println(transaction.invoice)
        println(transaction.paymentHash)
    }
    is NwcResult.Failure -> {
        println("Invoice creation failed: ${result.error.message}")
    }
}
```

Use `descriptionHash` instead of `description` when your invoice description is
large and the wallet expects a hash.

## Look Up an Invoice

Look up by invoice:

```kotlin
val result = client.lookupInvoice(
    LookupInvoiceParams(invoice = bolt11)
)
```

Or by payment hash:

```kotlin
val result = client.lookupInvoice(
    LookupInvoiceParams(paymentHash = paymentHash)
)
```

`LookupInvoiceParams` requires at least one of `paymentHash` or `invoice`.

## List Transactions

```kotlin
val params = ListTransactionsParams(
    limit = 25,
    offset = 0,
    unpaid = false,
    type = TransactionType.OUTGOING,
)

when (val result = client.listTransactions(params)) {
    is NwcResult.Success -> {
        result.value.forEach { transaction ->
            println("${transaction.createdAt}: ${transaction.amount}")
        }
    }
    is NwcResult.Failure -> {
        println("Could not list transactions: ${result.error.message}")
    }
}
```

The `from` and `until` filters are Unix timestamps in seconds.

## Send a Keysend Payment

```kotlin
val params = KeysendParams(
    pubkey = destinationNodePubkey,
    amount = Amount.fromSats(1_000),
)

when (val result = client.payKeysend(params)) {
    is NwcResult.Success -> println(result.value.preimage)
    is NwcResult.Failure -> println(result.error.message)
}
```

Include TLV records when needed:

```kotlin
val params = KeysendParams(
    pubkey = destinationNodePubkey,
    amount = Amount.fromSats(1_000),
    tlvRecords = listOf(
        TlvRecord(type = 34349334, value = metadataHex),
    ),
)
```

## Pay Multiple Invoices

```kotlin
val items = listOf(
    MultiPayInvoiceItem(id = "first", invoice = firstInvoice),
    MultiPayInvoiceItem(id = "second", invoice = secondInvoice),
)

when (val result = client.multiPayInvoice(items)) {
    is NwcResult.Success -> {
        result.value.forEach { (id, itemResult) ->
            when (itemResult) {
                is MultiPayItemResult.Success -> {
                    println("$id paid: ${itemResult.result.preimage}")
                }
                is MultiPayItemResult.Failed -> {
                    println("$id failed: ${itemResult.error.message}")
                }
            }
        }
    }
    is NwcResult.Failure -> {
        println("Batch failed: ${result.error.message}")
    }
}
```

Each item needs a stable `id`; the wallet returns item responses by that ID.

## Pay Multiple Keysends

```kotlin
val items = listOf(
    MultiKeysendItem(
        id = "alice",
        pubkey = aliceNodePubkey,
        amount = Amount.fromSats(500),
    ),
    MultiKeysendItem(
        id = "bob",
        pubkey = bobNodePubkey,
        amount = Amount.fromSats(750),
    ),
)

val result = client.multiPayKeysend(items)
```

Handle the returned `Map<String, MultiPayItemResult>` the same way as
`multiPayInvoice`.

## Collect Notifications

Check support first:

```kotlin
val supportsNotifications = client.walletInfo.value.supportsNotifications
```

Collect while the client is alive:

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

Notifications are live events only. They are not replayed after app restart.

## Branch on Wallet Errors

Wallet-defined NIP-47 failures are wrapped as `NwcError.WalletError`:

```kotlin
when (val result = client.payInvoice(invoice = bolt11)) {
    is NwcResult.Success -> println(result.value.preimage)
    is NwcResult.Failure -> when (val error = result.error) {
        is NwcError.WalletError -> when (error.code) {
            NwcErrorCode.INSUFFICIENT_BALANCE -> println("Not enough balance")
            NwcErrorCode.QUOTA_EXCEEDED -> println("Spending quota exceeded")
            NwcErrorCode.UNAUTHORIZED -> println("Connection is not authorized")
            else -> println(error.message)
        }
        else -> println(error.message)
    }
}
```

