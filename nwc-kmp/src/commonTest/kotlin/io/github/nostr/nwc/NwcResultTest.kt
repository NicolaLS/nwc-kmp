package io.github.nostr.nwc

import io.github.nostr.nwc.model.NwcFailure
import io.github.nostr.nwc.model.NwcResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NwcResultTest {

    @Test
    fun runNwcCatchingWrapsExceptions() {
        val result = runNwcCatching { throw NwcException("boom") }
        assertTrue(result is NwcResult.Failure)
        val failure = (result as NwcResult.Failure).failure
        assertTrue(failure is NwcFailure.Unknown)
    }

    @Test
    fun getOrThrowReturnsValue() {
        val value = NwcResult.Success(42).getOrThrow()
        assertEquals(42, value)
    }
}
