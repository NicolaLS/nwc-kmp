package io.github.nostr.nwc.model

enum class RelayConnectionStatus {
    CONNECTING,
    READY,
    DISCONNECTED,
    FAILED
}

enum class ConnectionHealth {
    CONNECTING,
    READY,
    DEGRADED,
    FAILED,
    DISCONNECTED
}

data class NwcConnectionState(
    val relays: Map<String, RelayConnectionStatus>,
    val overall: ConnectionHealth
) {
    companion object {
        val Empty = NwcConnectionState(emptyMap(), ConnectionHealth.DISCONNECTED)

        fun fromRelayStates(states: Map<String, RelayConnectionStatus>): NwcConnectionState {
            if (states.isEmpty()) {
                return Empty
            }
            val overall = when {
                states.values.any { it == RelayConnectionStatus.FAILED } -> ConnectionHealth.FAILED
                states.values.any { it == RelayConnectionStatus.CONNECTING } -> ConnectionHealth.CONNECTING
                states.values.any { it == RelayConnectionStatus.DISCONNECTED } -> ConnectionHealth.DEGRADED
                states.values.all { it == RelayConnectionStatus.READY } -> ConnectionHealth.READY
                else -> ConnectionHealth.DEGRADED
            }
            return NwcConnectionState(states, overall)
        }
    }
}
