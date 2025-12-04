package io.github.nostr.nwc

import io.github.nostr.nwc.model.NwcFailure
import nostr.core.session.ConnectionFailureReason
import nostr.core.session.EngineError

internal fun Throwable.toFailure(): NwcFailure = when (this) {
    is NwcTimeoutException -> NwcFailure.Timeout(message)
    is NwcNetworkException -> NwcFailure.Network(message, throwable = cause)
    is NwcEncryptionException -> NwcFailure.EncryptionUnsupported(message ?: "Encryption unsupported")
    is NwcRequestException -> NwcFailure.Wallet(error)
    is NwcProtocolException -> NwcFailure.Protocol(message ?: "Protocol violation")
    is NwcException -> NwcFailure.Unknown(message, cause)
    else -> NwcFailure.Network(message, throwable = this)
}

internal fun EngineError.toFailure(): NwcFailure = when (this) {
    is EngineError.ConnectionFailure -> NwcFailure.Network(
        message = message,
        reason = reason,
        closeCode = closeCode,
        closeReason = closeReason,
        engineCause = cause
    )
    is EngineError.ProtocolViolation -> NwcFailure.Protocol(description)
    is EngineError.OutboundFailure -> NwcFailure.Network(
        message = reason,
        reason = ConnectionFailureReason.StreamFailure
    )
}

internal fun NwcFailure.toException(): NwcException = when (this) {
    NwcFailure.None -> NwcException("No failure")
    is NwcFailure.Network -> {
        val base = message ?: reason?.description ?: "Network failure"
        val detail = buildString {
            reason?.let { append("reason=${it.description}") }
            closeCode?.let {
                if (isNotEmpty()) append(", ")
                append("closeCode=").append(it)
            }
            closeReason?.let {
                if (isNotEmpty()) append(", ")
                append("closeReason=").append(it)
            }
            engineCause?.let {
                if (isNotEmpty()) append(", ")
                append("engineCause=").append(it)
            }
        }
        val messageWithDetail = if (detail.isEmpty()) base else "$base ($detail)"
        NwcNetworkException(messageWithDetail, throwable)
    }
    is NwcFailure.Timeout -> NwcTimeoutException(message ?: "Request timed out")
    is NwcFailure.Wallet -> NwcRequestException(error)
    is NwcFailure.Protocol -> NwcProtocolException(message)
    is NwcFailure.EncryptionUnsupported -> NwcEncryptionException(message)
    is NwcFailure.Unknown -> NwcException(message ?: "Unknown failure", cause)
}
