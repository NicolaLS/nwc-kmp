package io.github.nostr.nwc.testing

import io.github.nostr.nwc.internal.MethodNames
import io.github.nostr.nwc.internal.string
import io.github.nostr.nwc.model.BitcoinAmount
import io.github.nostr.nwc.model.NwcError
import io.github.nostr.nwc.model.TransactionType
import io.github.nostr.nwc.model.WalletNotification
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScriptedWalletHarnessTest {

    @Test
    fun payInvoiceDefaultFlowRecordsTransactionAndNotification() {
        val harness = ScriptedWalletHarness()
        val params = buildJsonObject {
            put("invoice", "lnbc1scripted")
            put("amount", 5_000)
            put("metadata", buildJsonObject { put("description", "coffee") })
        }

        val response = harness.handle(MethodNames.PAY_INVOICE, params)

        assertEquals("result", response.resultType)
        val resultObj = response.result as? JsonObject ?: error("Expected JsonObject payload")
        assertTrue(resultObj.containsKey("preimage"))

        val transactions = harness.recordedTransactions()
        assertEquals(1, transactions.size)
        val transaction = transactions.first()
        assertEquals(TransactionType.OUTGOING, transaction.type)
        assertEquals(BitcoinAmount.fromMsats(5_000), transaction.amount)
        assertEquals("coffee", transaction.description)

        val notifications = harness.recordedNotifications()
        assertEquals(1, notifications.size)
        assertIs<WalletNotification.PaymentSent>(notifications.first())
    }

    @Test
    fun payInvoiceFailureUsesScriptedError() {
        val harness = ScriptedWalletHarness()
        harness.enqueuePayInvoiceError(NwcError("WALLET_ERROR", "insufficient balance")) { request ->
            assertEquals("lnbc1fail", request.invoice)
        }

        val response = harness.handle(
            MethodNames.PAY_INVOICE,
            buildJsonObject { put("invoice", "lnbc1fail") }
        )

        assertEquals("error", response.resultType)
        assertEquals("WALLET_ERROR", response.error?.code)
        assertTrue(harness.recordedTransactions().isEmpty())
    }

    @Test
    fun listTransactionsRespectsFilters() {
        val harness = ScriptedWalletHarness()
        harness.setTimestampCursor(100L)

        val makeResponse = harness.handle(
            MethodNames.MAKE_INVOICE,
            buildJsonObject {
                put("amount", 2_000)
                put("description", "latte")
                put("expiry", 600)
            }
        )
        val invoice = (makeResponse.result as JsonObject).string("invoice")!!

        harness.enqueuePayInvoiceSuccess()
        harness.handle(
            MethodNames.PAY_INVOICE,
            buildJsonObject {
                put("invoice", invoice)
                put("amount", 2_000)
            }
        )

        val defaultList = harness.handle(MethodNames.LIST_TRANSACTIONS, buildJsonObject { })
        val defaultArray = (defaultList.result as JsonObject)["transactions"] as JsonArray
        assertEquals(1, defaultArray.size, "Pending invoices should be filtered by default")

        val allList = harness.handle(
            MethodNames.LIST_TRANSACTIONS,
            buildJsonObject { put("unpaid", true) }
        )
        val allArray = (allList.result as JsonObject)["transactions"] as JsonArray
        assertEquals(2, allArray.size, "Including unpaid should return pending invoices too")

        val outgoingOnly = harness.handle(
            MethodNames.LIST_TRANSACTIONS,
            buildJsonObject {
                put("type", "outgoing")
                put("limit", 1)
            }
        )
        val outgoingArray = (outgoingOnly.result as JsonObject)["transactions"] as JsonArray
        assertEquals(1, outgoingArray.size)
    }
}
