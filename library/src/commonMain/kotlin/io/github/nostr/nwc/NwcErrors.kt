package io.github.nostr.nwc

import io.github.nostr.nwc.model.NwcFailure
import nostr.core.session.EngineError

internal fun Throwable.toFailure(): NwcFailure = when (this) {
    is NwcTimeoutException -> NwcFailure.Timeout(message)
    is NwcEncryptionException -> NwcFailure.EncryptionUnsupported(message ?: "Encryption unsupported")
    is NwcRequestException -> NwcFailure.Wallet(error)
    is NwcProtocolException -> NwcFailure.Protocol(message ?: "Protocol violation")
    is NwcException -> NwcFailure.Unknown(message, cause)
    else -> NwcFailure.Network(message, this)
}

internal fun EngineError.toFailure(): NwcFailure = when (this) {
    is EngineError.ConnectionFailure -> NwcFailure.Network(message, null)
    is EngineError.ProtocolViolation -> NwcFailure.Protocol(description)
    is EngineError.OutboundFailure -> NwcFailure.Network(reason, null)
}

internal fun NwcFailure.toException(): NwcException = when (this) {
    NwcFailure.None -> NwcException("No failure")
    is NwcFailure.Network -> NwcException(message ?: "Network failure", cause)
    is NwcFailure.Timeout -> NwcTimeoutException(message ?: "Request timed out")
    is NwcFailure.Wallet -> NwcRequestException(error)
    is NwcFailure.Protocol -> NwcProtocolException(message)
    is NwcFailure.EncryptionUnsupported -> NwcEncryptionException(message)
    is NwcFailure.Unknown -> NwcException(message ?: "Unknown failure", cause)
}
