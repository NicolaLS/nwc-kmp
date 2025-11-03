package io.github.nostr.nwc.internal

import io.github.nostr.nwc.NwcEncryptionException
import io.github.nostr.nwc.model.EncryptionScheme
import io.github.nostr.nwc.model.WalletMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EncryptionNegotiationTest {

    private fun preferredOrder() = listOf(EncryptionScheme.Nip44V2, EncryptionScheme.Nip04)

    @Test
    fun prefersNip44WhenAvailable() {
        val metadata = WalletMetadata(
            capabilities = emptySet(),
            encryptionSchemes = setOf(EncryptionScheme.Nip44V2, EncryptionScheme.Nip04),
            notificationTypes = emptySet()
        )

        val selected = selectPreferredEncryption(metadata, preferredOrder())

        assertEquals(EncryptionScheme.Nip44V2, selected)
    }

    @Test
    fun fallsBackToNip04WhenOnlyLegacyAdvertised() {
        val metadata = WalletMetadata(
            capabilities = emptySet(),
            encryptionSchemes = setOf(EncryptionScheme.Nip04),
            notificationTypes = emptySet()
        )

        val selected = selectPreferredEncryption(metadata, preferredOrder())

        assertEquals(EncryptionScheme.Nip04, selected)
    }

    @Test
    fun defaultsToNip04WhenTagMissing() {
        val metadata = WalletMetadata(
            capabilities = emptySet(),
            encryptionSchemes = setOf(EncryptionScheme.Nip04),
            notificationTypes = emptySet(),
            encryptionDefaultedToNip04 = true
        )

        val selected = selectPreferredEncryption(metadata, preferredOrder())

        assertEquals(EncryptionScheme.Nip04, selected)
    }

    @Test
    fun throwsWhenNoSupportedSchemes() {
        val metadata = WalletMetadata(
            capabilities = emptySet(),
            encryptionSchemes = setOf(EncryptionScheme.Unknown("nip99")),
            notificationTypes = emptySet()
        )

        assertFailsWith<NwcEncryptionException> {
            selectPreferredEncryption(metadata, preferredOrder())
        }
    }
}
