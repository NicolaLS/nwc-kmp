package io.github.nostr.nwc.model

import kotlin.test.Test
import kotlin.test.assertEquals

class NwcConnectionStateTest {

    @Test
    fun overallDegradedWhenDisconnected() {
        val state = NwcConnectionState.fromRelayStates(
            mapOf(
                "wss://a" to RelayConnectionStatus.READY,
                "wss://b" to RelayConnectionStatus.DISCONNECTED
            )
        )
        assertEquals(ConnectionHealth.DEGRADED, state.overall)
    }

    @Test
    fun overallFailedWhenAnyFailed() {
        val state = NwcConnectionState.fromRelayStates(
            mapOf(
                "wss://a" to RelayConnectionStatus.FAILED,
                "wss://b" to RelayConnectionStatus.CONNECTING
            )
        )
        assertEquals(ConnectionHealth.FAILED, state.overall)
    }

    @Test
    fun overallReadyWhenAllReady() {
        val state = NwcConnectionState.fromRelayStates(
            mapOf(
                "wss://a" to RelayConnectionStatus.READY,
                "wss://b" to RelayConnectionStatus.READY
            )
        )
        assertEquals(ConnectionHealth.READY, state.overall)
    }
}
