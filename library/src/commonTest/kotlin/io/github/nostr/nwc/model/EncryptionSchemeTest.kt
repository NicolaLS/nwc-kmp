package io.github.nostr.nwc.model

import kotlin.test.Test
import kotlin.test.assertEquals

class EncryptionSchemeTest {

    @Test
    fun parseListHandlesWhitespace() {
        val parsed = EncryptionScheme.parseList("nip44_v2   nip04")
        assertEquals(setOf(EncryptionScheme.Nip44V2, EncryptionScheme.Nip04), parsed)
    }

    @Test
    fun parseListIgnoresUnknownEntries() {
        val parsed = EncryptionScheme.parseList("nip44_v2 experimental")
        assertEquals(setOf(EncryptionScheme.Nip44V2), parsed)
    }
}
