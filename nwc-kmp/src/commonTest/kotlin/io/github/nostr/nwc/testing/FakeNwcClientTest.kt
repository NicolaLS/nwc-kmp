package io.github.nostr.nwc.testing

import io.github.nostr.nwc.model.BitcoinAmount
import io.github.nostr.nwc.model.ListTransactionsParams
import io.github.nostr.nwc.model.NwcFailure
import io.github.nostr.nwc.model.NwcResult
import io.github.nostr.nwc.model.PayInvoiceParams
import io.github.nostr.nwc.model.PayInvoiceResult
import io.github.nostr.nwc.model.Transaction
import io.github.nostr.nwc.model.TransactionState
import io.github.nostr.nwc.model.TransactionType
import io.github.nostr.nwc.model.WalletMetadata
import io.github.nostr.nwc.model.WalletNotification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject

class FakeNwcClientTest {

    @Test
    fun refreshWalletMetadataUpdatesState() = runTest {
        val metadata = WalletMetadata(emptySet(), emptySet(), emptySet())
        val fake = FakeNwcClient()
        fake.refreshWalletMetadata.enqueue(NwcResult.Success(metadata))

        val result = fake.refreshWalletMetadata(timeoutMillis = 150)

        val success = assertIs<NwcResult.Success<WalletMetadata>>(result)
        assertEquals(metadata, success.value)
        assertEquals(metadata, fake.walletMetadata.value)
        assertEquals(listOf(150L), fake.refreshWalletMetadata.calls)
    }

    @Test
    fun payInvoiceUsesQueueAndTracksCalls() = runTest {
        val fake = FakeNwcClient()
        val params = PayInvoiceParams(invoice = "lnbc1invoice")
        val expected = PayInvoiceResult(
            preimage = "deadbeef",
            feesPaid = BitcoinAmount.fromMsats(2500)
        )
        fake.payInvoice.enqueue(NwcResult.Success(expected))

        val result = fake.payInvoice(params, timeoutMillis = 200)

        val success = assertIs<NwcResult.Success<PayInvoiceResult>>(result)
        assertEquals(expected, success.value)
        assertEquals(listOf(params to 200L), fake.payInvoice.calls)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun notificationsEmitToCollectors() = runTest {
        val fake = FakeNwcClient()
        val transaction = Transaction(
            type = TransactionType.INCOMING,
            state = TransactionState.SETTLED,
            invoice = "lnbc1invoice",
            description = "coffee",
            descriptionHash = null,
            preimage = "preimage",
            paymentHash = "hash",
            amount = BitcoinAmount.fromSats(1),
            feesPaid = null,
            createdAt = 0L,
            expiresAt = null,
            settledAt = 5L,
            metadata = buildJsonObject { }
        )
        val notification = WalletNotification.PaymentReceived(transaction)
        val deferred = async { fake.notifications.first() }
        advanceUntilIdle()
        fake.emitNotification(notification)

        assertEquals(notification, deferred.await())
    }

    @Test
    fun defaultResultsReturnDescriptiveFailure() = runTest {
        val fake = FakeNwcClient()

        val result = fake.listTransactions(ListTransactionsParams(), timeoutMillis = 0)

        val failure = assertIs<NwcResult.Failure>(result)
        assertTrue(failure.failure is NwcFailure.Unknown)
        assertTrue(failure.failure.message?.contains("listTransactions") == true)
    }
}
