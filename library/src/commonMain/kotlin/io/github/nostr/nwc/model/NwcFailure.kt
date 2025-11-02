package io.github.nostr.nwc.model

import nostr.core.session.ConnectionFailureReason

sealed class NwcFailure {
    object None : NwcFailure()
    data class Network(
        val message: String?,
        val reason: ConnectionFailureReason? = null,
        val closeCode: Int? = null,
        val closeReason: String? = null,
        val engineCause: String? = null,
        val throwable: Throwable? = null
    ) : NwcFailure()
    data class Timeout(val message: String?) : NwcFailure()
    data class Wallet(val error: NwcError) : NwcFailure()
    data class Protocol(val message: String) : NwcFailure()
    data class EncryptionUnsupported(val message: String) : NwcFailure()
    data class Unknown(val message: String?, val cause: Throwable? = null) : NwcFailure()
}
