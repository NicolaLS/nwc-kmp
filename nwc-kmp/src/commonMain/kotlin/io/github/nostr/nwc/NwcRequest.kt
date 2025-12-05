package io.github.nostr.nwc

import io.github.nostr.nwc.model.NwcFailure
import io.github.nostr.nwc.model.NwcRequestState
import io.github.nostr.nwc.model.NwcResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

/**
 * A controllable NWC request that can be observed and cancelled.
 *
 * This wrapper provides:
 * - [state]: A [StateFlow] emitting [NwcRequestState.Loading] initially, then
 *   [NwcRequestState.Success] or [NwcRequestState.Failure] when the wallet responds.
 * - [cancel]: Stop listening for the response and clean up resources.
 *
 * The library does NOT enforce a timeout - consumers decide how long to wait.
 * Use Kotlin's flow operators like [withTimeout] or check state periodically.
 *
 * Example usage:
 * ```kotlin
 * val request = client.payInvoiceRequest(params)
 *
 * // Option 1: Wait with your own timeout
 * val result = withTimeoutOrNull(30.seconds) {
 *     request.state.first { it !is NwcRequestState.Loading }
 * }
 * if (result == null) {
 *     // Timeout - update UI to show "pending" but keep listening
 *     showPendingUI()
 * }
 *
 * // Option 2: Observe indefinitely
 * request.state.collect { state ->
 *     when (state) {
 *         NwcRequestState.Loading -> showLoading()
 *         is NwcRequestState.Success -> showSuccess(state.value)
 *         is NwcRequestState.Failure -> showError(state.failure)
 *     }
 * }
 *
 * // When done (e.g., user navigates away)
 * request.cancel()
 * ```
 */
class NwcRequest<T> internal constructor(
    /**
     * Observable state of the request.
     *
     * Emits:
     * - [NwcRequestState.Loading] immediately when created
     * - [NwcRequestState.Success] when wallet responds successfully
     * - [NwcRequestState.Failure] if the request fails (wallet rejection, protocol error, etc.)
     *
     * The flow never completes on its own - call [cancel] to clean up.
     */
    val state: StateFlow<NwcRequestState<T>>,

    /**
     * The correlation ID for this request (the nostr event ID).
     * Can be used for logging/debugging purposes.
     */
    val requestId: String,

    private val job: Job
) {
    /**
     * Cancel the request and stop listening for responses.
     *
     * Call this when:
     * - The user navigates away from the screen
     * - You no longer care about the result
     * - You want to free up resources
     *
     * After calling cancel, the [state] flow will stop receiving updates.
     */
    fun cancel() {
        job.cancel()
    }

    /**
     * Whether the request is still active (not cancelled and not completed).
     */
    val isActive: Boolean get() = job.isActive

    /**
     * Suspends until the request completes (success or failure) or times out.
     *
     * @param timeout Maximum time to wait for a result
     * @return The final [NwcRequestState] (Success or Failure), or null if timed out
     */
    suspend fun awaitResult(timeout: Duration): NwcRequestState<T>? {
        return try {
            withTimeout(timeout) {
                state.first { !it.isLoading }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            null
        }
    }

    /**
     * Suspends until the request completes (success or failure).
     * Waits indefinitely - use [awaitResult] with a timeout for bounded waiting.
     *
     * @return The final [NwcRequestState] (Success or Failure)
     */
    suspend fun awaitResult(): NwcRequestState<T> {
        return state.first { !it.isLoading }
    }

    /**
     * Convenience method to get the result as [NwcResult] with a timeout.
     *
     * @param timeout Maximum time to wait
     * @return [NwcResult.Success] or [NwcResult.Failure], or a Timeout failure if timed out
     */
    suspend fun toResult(timeout: Duration): NwcResult<T> {
        val result = awaitResult(timeout)
        return when (result) {
            is NwcRequestState.Success -> NwcResult.Success(result.value)
            is NwcRequestState.Failure -> NwcResult.Failure(result.failure)
            NwcRequestState.Loading, null -> NwcResult.Failure(
                NwcFailure.Timeout("Request timed out after $timeout")
            )
        }
    }
}
