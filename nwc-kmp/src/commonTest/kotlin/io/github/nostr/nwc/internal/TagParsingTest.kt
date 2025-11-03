package io.github.nostr.nwc.internal

import io.github.nostr.nwc.model.EncryptionScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TagParsingTest {

    @Test
    fun parsesMultiValueEncryptionTag() {
        val info = parseEncryptionTagValues(listOf("nip04", "nip44_v2"))

        assertEquals(listOf(EncryptionScheme.Nip04, EncryptionScheme.Nip44V2), info.schemes)
        assertFalse(info.defaultedToNip04)
    }

    @Test
    fun parsesSpaceSeparatedEncryptionTag() {
        val info = parseEncryptionTagValues(listOf("nip44_v2 nip04"))

        assertEquals(listOf(EncryptionScheme.Nip44V2, EncryptionScheme.Nip04), info.schemes)
        assertFalse(info.defaultedToNip04)
    }

    @Test
    fun defaultsToNip04WhenMissing() {
        val info = parseEncryptionTagValues(null)

        assertTrue(info.schemes.isEmpty())
        assertTrue(info.defaultedToNip04)
    }

    @Test
    fun handlesUnknownSchemes() {
        val info = parseEncryptionTagValues(listOf("experimental"))

        assertEquals(listOf(EncryptionScheme.Unknown("experimental")), info.schemes)
        assertTrue(!info.defaultedToNip04)
    }
}
