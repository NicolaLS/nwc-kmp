package io.github.nostr.nwc.model

sealed class NwcFailure {
    object None : NwcFailure()
    data class Network(val message: String?, val cause: Throwable? = null) : NwcFailure()
    data class Timeout(val message: String?) : NwcFailure()
    data class Wallet(val error: NwcError) : NwcFailure()
    data class Protocol(val message: String) : NwcFailure()
    data class EncryptionUnsupported(val message: String) : NwcFailure()
    data class Unknown(val message: String?, val cause: Throwable? = null) : NwcFailure()
}
