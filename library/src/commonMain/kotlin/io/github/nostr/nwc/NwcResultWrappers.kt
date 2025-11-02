package io.github.nostr.nwc

import io.github.nostr.nwc.model.NwcFailure
import io.github.nostr.nwc.model.NwcResult

internal inline fun <T> runNwcCatching(block: () -> T): NwcResult<T> = try {
    NwcResult.Success(block())
} catch (failure: Throwable) {
    NwcResult.Failure(failure.toFailure())
}

internal fun failureOf(failure: NwcFailure): NwcResult.Failure = NwcResult.Failure(failure)
