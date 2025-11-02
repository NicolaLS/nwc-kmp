package io.github.nostr.nwc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking

class NwcSessionManagerTest {

    private val samplePubkey = "b889ff5b1513b641e2a139f661a661364979c5beee91842f8f0ef42ab558e9d4"
    private val sampleSecret = "71a8c14c1407c113601079c4302dab36460f0ccd0ad506f1f2dc73b5100e4f3c"
    private val sampleUri = "nostr+walletconnect://$samplePubkey?relay=wss://relay.example&secret=$sampleSecret"

    @Test
    fun acquireReusesSessionsUntilReleased() = runBlocking {
        val manager = NwcSessionManager.create()
        val first = manager.acquire(sampleUri)
        val second = manager.acquire(sampleUri)

        assertSame(first, second)
        assertEquals(1, manager.activeSessionCount())

        manager.release(first)
        // still one session tracked due to second reference
        assertEquals(1, manager.activeSessionCount())

        manager.release(second)
        assertEquals(0, manager.activeSessionCount())

        val third = manager.acquire(sampleUri)
        assertNotSame(first, third)

        manager.release(third)
        manager.shutdown()
    }
}
