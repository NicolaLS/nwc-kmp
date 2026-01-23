package io.github.nostr.nwc.model

/**
 * Represents the state of an NWC request that can be observed over time.
 *
 * Unlike [NwcResult] which represents a final outcome, [NwcRequestState] models
 * the lifecycle of a request including the pending/loading phase.
 *
 * This enables consumers to:
 * - Update UI immediately when a request starts (Loading)
 * - Continue observing indefinitely for slow wallet responses
 * - Decide their own timeout strategy using Kotlin's flow operators
 */
sealed class NwcRequestState<out T> {
    /**
     * The request has been sent and is awaiting a response.
     */
    data object Loading : NwcRequestState<Nothing>()

    /**
     * The request completed successfully with a result.
     */
    data class Success<T>(val value: T) : NwcRequestState<T>()

    /**
     * The request failed with an error.
     * Note: This represents actual failures (wallet rejected, protocol error, etc.),
     * not timeouts. There is no timeout state - consumers control how long to wait.
     */
    data class Failure(val failure: NwcFailure) : NwcRequestState<Nothing>()

    val isLoading: Boolean get() = this is Loading
    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    /**
     * Returns the success value or null if not successful.
     */
    fun getOrNull(): T? = when (this) {
        is Success -> value
        else -> null
    }

    /**
     * Converts a terminal state to [NwcResult], or returns null if still loading.
     */
    fun toResultOrNull(): NwcResult<T>? = when (this) {
        is Loading -> null
        is Success -> NwcResult.Success(value)
        is Failure -> NwcResult.Failure(failure)
    }

    /**
     * Fold over the state with handlers for each case.
     */
    inline fun <R> fold(
        onLoading: () -> R,
        onSuccess: (T) -> R,
        onFailure: (NwcFailure) -> R
    ): R = when (this) {
        is Loading -> onLoading()
        is Success -> onSuccess(value)
        is Failure -> onFailure(failure)
    }
}
