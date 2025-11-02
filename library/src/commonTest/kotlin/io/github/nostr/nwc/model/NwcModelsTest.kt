package io.github.nostr.nwc.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NwcModelsTest {

    @Test
    fun capabilityParsingIncludesUnknown() {
        val parsed = NwcCapability.parseAll(listOf("pay_invoice", "make_invoice", "custom_method"))
        assertTrue(parsed.contains(NwcCapability.PayInvoice))
        assertTrue(parsed.contains(NwcCapability.MakeInvoice))
        val unknown = parsed.filterIsInstance<NwcCapability.Unknown>().single()
        assertEquals("custom_method", unknown.value)
    }

    @Test
    fun notificationParsingIncludesUnknown() {
        val parsed = NwcNotificationType.parseAll(listOf("payment_received", "extra"))
        assertTrue(parsed.contains(NwcNotificationType.PaymentReceived))
        val unknown = parsed.filterIsInstance<NwcNotificationType.Unknown>().single()
        assertEquals("extra", unknown.value)
    }

    @Test
    fun encryptionParsingAcceptsUnknown() {
        val parsed = EncryptionScheme.parseList("nip44_v2 legacy")
        assertTrue(parsed.contains(EncryptionScheme.Nip44V2))
        val unknown = parsed.filterIsInstance<EncryptionScheme.Unknown>().single()
        assertEquals("legacy", unknown.value)
    }
}
