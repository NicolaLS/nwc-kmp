package io.github.nostr.nwc.model

import io.github.nostr.nwc.NwcUri
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

    @Test
    fun walletDescriptorUnifiesMetadataAndInfo() {
        val metadata = WalletMetadata(
            capabilities = setOf(NwcCapability.PayInvoice),
            encryptionSchemes = setOf(EncryptionScheme.Nip44V2),
            notificationTypes = setOf(NwcNotificationType.PaymentReceived)
        )
        val info = GetInfoResult(
            alias = "Alice",
            color = "#ffffff",
            pubkey = "abc",
            network = Network.MAINNET,
            blockHeight = 100L,
            blockHash = "hash",
            methods = setOf(NwcCapability.MakeInvoice),
            notifications = setOf(NwcNotificationType.PaymentSent)
        )
        val descriptor = NwcWalletDescriptor(
            uri = NwcUri.parse("nostr+walletconnect://abc?relay=wss://relay.example&secret=71a8c14c1407c113601079c4302dab36460f0ccd0ad506f1f2dc73b5100e4f3c"),
            metadata = metadata,
            info = info,
            negotiatedEncryption = EncryptionScheme.Nip44V2,
            relays = listOf("wss://relay.example"),
            lud16 = "alice@example.com"
        )
        assertTrue(descriptor.capabilities.contains(NwcCapability.PayInvoice))
        assertTrue(descriptor.capabilities.contains(NwcCapability.MakeInvoice))
        assertTrue(descriptor.notifications.contains(NwcNotificationType.PaymentReceived))
        assertTrue(descriptor.notifications.contains(NwcNotificationType.PaymentSent))
    }
}
