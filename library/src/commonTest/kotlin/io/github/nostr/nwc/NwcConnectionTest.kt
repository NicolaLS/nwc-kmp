package io.github.nostr.nwc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NwcConnectionTest {

    private val samplePubkey = "b889ff5b1513b641e2a139f661a661364979c5beee91842f8f0ef42ab558e9d4"
    private val sampleSecret = "71a8c14c1407c113601079c4302dab36460f0ccd0ad506f1f2dc73b5100e4f3c"

    @Test
    fun parseValidUriWithMultipleRelays() {
        val uri = "nostr+walletconnect://$samplePubkey?relay=wss%3A%2F%2Frelay.damus.io&relay=wss://example.com&secret=$sampleSecret&lud16=alice@example.com"

        val credentials = parseNwcUri(uri)

        assertEquals(samplePubkey, credentials.walletPublicKey.toString())
        assertEquals(listOf("wss://relay.damus.io", "wss://example.com"), credentials.relays)
        assertEquals(sampleSecret, credentials.secretKey.hex)
        assertEquals("alice@example.com", credentials.lud16)
    }

    @Test
    fun parseTrimsRelayValues() {
        val uri = "nostr+walletconnect://$samplePubkey?relay= wss://relay.example &secret=$sampleSecret"

        val credentials = parseNwcUri(uri)

        assertEquals(listOf("wss://relay.example"), credentials.relays)
    }

    @Test
    fun parseComponentsExposeOriginalAndFields() {
        val original = "nostr+walletconnect://$samplePubkey?relay=wss://relay.example&secret=$sampleSecret&lud16=alice@example.com"
        val uri = "  $original  "

        val parsed = parseNwcUriComponents(uri)

        assertEquals(original, parsed.original)
        assertEquals(samplePubkey, parsed.walletPublicKey.toString())
        assertEquals(listOf("wss://relay.example"), parsed.relays)
        assertEquals(sampleSecret, parsed.secretKey.hex)
        assertEquals("alice@example.com", parsed.lud16)
        assertEquals(parsed.toCredentials(), parseNwcUri(uri))
    }

    @Test
    fun missingSecretFails() {
        val uri = "nostr+walletconnect://$samplePubkey?relay=wss://relay.example"

        assertFailsWith<IllegalArgumentException> {
            parseNwcUri(uri)
        }
    }

    @Test
    fun invalidSchemeFails() {
        val uri = "nostr://$samplePubkey?relay=wss://relay.example&secret=$sampleSecret"

        assertFailsWith<IllegalArgumentException> {
            parseNwcUri(uri)
        }
    }
}
