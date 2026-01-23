package io.github.nostr.nwc.model

import io.github.nostr.nwc.toException

sealed class NwcResult<out T> {
    data class Success<T>(val value: T) : NwcResult<T>()
    data class Failure(val failure: NwcFailure) : NwcResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw failure.toException()
    }

    inline fun <R> fold(onSuccess: (T) -> R, onFailure: (NwcFailure) -> R): R = when (this) {
        is Success -> onSuccess(value)
        is Failure -> onFailure(failure)
    }
}
