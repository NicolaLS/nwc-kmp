package io.github.nostr.nwc.internal

import io.github.nostr.nwc.logging.NwcLog
import io.github.nostr.nwc.model.NwcError
import io.github.nostr.nwc.model.RawResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages pending NWC requests and their completion.
 * Thread-safe via internal mutex.
 */
internal class PendingRequestManager(private val logTag: String = "PendingRequestManager") {

    sealed interface PendingRequest {
        val method: String
        val deferred: CompletableDeferred<*>

        class Single(
            override val method: String,
            override val deferred: CompletableDeferred<RawResponse>
        ) : PendingRequest

        class Multi(
            override val method: String,
            val expectedKeys: Set<String>,
            val results: MutableMap<String, RawResponse>,
            override val deferred: CompletableDeferred<Map<String, RawResponse>>
        ) : PendingRequest
    }

    /**
     * Result of attempting to complete a request with a response.
     */
    sealed class CompletionResult {
        /** Request not found in pending map */
        data object NotFound : CompletionResult()

        /** Single request completed */
        data object SingleCompleted : CompletionResult()

        /** Multi request received partial response, waiting for more */
        data object MultiPartial : CompletionResult()

        /** Multi request completed with all expected responses */
        data object MultiCompleted : CompletionResult()
    }

    private val mutex = Mutex()
    private val requests = mutableMapOf<String, PendingRequest>()

    /**
     * Register a pending request.
     */
    suspend fun register(requestId: String, pending: PendingRequest) {
        mutex.withLock {
            requests[requestId] = pending
        }
    }

    /**
     * Remove a pending request if it matches the given deferred.
     * @return true if removed, false if not found or deferred doesn't match
     */
    suspend fun remove(requestId: String, deferred: CompletableDeferred<*>): Boolean {
        return mutex.withLock {
            val current = requests[requestId]
            if (current?.deferred == deferred) {
                requests.remove(requestId)
                true
            } else {
                false
            }
        }
    }

    /**
     * Check if there's exactly one pending request and return its ID.
     */
    suspend fun getSinglePendingId(): String? {
        return mutex.withLock {
            requests.keys.singleOrNull()
        }
    }

    /**
     * Try to resolve a request ID from a response when the #e tag is missing.
     * Uses result_type matching against pending request methods.
     */
    suspend fun resolveRequestId(response: RawResponse): String? {
        val snapshot = mutex.withLock { requests.toMap() }
        if (snapshot.size == 1) return snapshot.keys.first()
        val byMethod = snapshot.filterValues { it.method == response.resultType }.keys
        if (byMethod.size == 1) return byMethod.first()
        return null
    }

    /**
     * Complete a single request with a response.
     * @return CompletionResult indicating what happened
     */
    suspend fun completeSingle(requestId: String, response: RawResponse): CompletionResult {
        return mutex.withLock {
            val pending = requests[requestId] as? PendingRequest.Single
                ?: return@withLock CompletionResult.NotFound

            if (!pending.deferred.isCompleted) {
                NwcLog.debug(logTag) { "Completing single response for event $requestId" }
                pending.deferred.complete(response)
            }
            requests.remove(requestId)
            CompletionResult.SingleCompleted
        }
    }

    /**
     * Add a response to a multi-request.
     * @param key The key identifying this response within the multi-request (from d tag or derived)
     * @return CompletionResult indicating if partial or fully completed
     */
    suspend fun addMultiResponse(requestId: String, key: String, response: RawResponse): CompletionResult {
        return mutex.withLock {
            val pending = requests[requestId] as? PendingRequest.Multi
                ?: return@withLock CompletionResult.NotFound

            if (pending.results.containsKey(key)) {
                return@withLock CompletionResult.MultiPartial
            }

            pending.results[key] = response

            if (pending.results.keys.containsAll(pending.expectedKeys)) {
                NwcLog.debug(logTag) { "Collected final multi response for event $requestId" }
                pending.deferred.complete(pending.results.toMap())
                requests.remove(requestId)
                CompletionResult.MultiCompleted
            } else {
                CompletionResult.MultiPartial
            }
        }
    }

    /**
     * Complete a request with an error.
     * @return true if a request was completed, false if not found
     */
    suspend fun completeWithError(requestId: String, error: NwcError): Boolean {
        return mutex.withLock {
            when (val pending = requests.remove(requestId)) {
                is PendingRequest.Single -> {
                    NwcLog.warn(logTag) { "Completing request $requestId with error ${error.code}" }
                    pending.deferred.complete(RawResponse("", null, error))
                    true
                }
                is PendingRequest.Multi -> {
                    NwcLog.warn(logTag) { "Completing multi request $requestId with error ${error.code}" }
                    pending.deferred.complete(
                        pending.expectedKeys.associateWith { RawResponse("", null, error) }
                    )
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Cancel all pending requests (for cleanup on close).
     */
    suspend fun cancelAll() {
        mutex.withLock {
            requests.values.forEach { it.deferred.cancel() }
            requests.clear()
        }
    }
}
