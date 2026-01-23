package io.github.nicolals.nwc

/**
 * Result type for NWC operations.
 *
 * Similar to Kotlin's [Result] but specialized for NWC errors.
 *
 * ## Usage
 *
 * ```kotlin
 * when (val result = client.getBalance()) {
 *     is NwcResult.Success -> println("Balance: ${result.value}")
 *     is NwcResult.Failure -> println("Error: ${result.error.message}")
 * }
 *
 * // Or use helpers
 * val balance = client.getBalance().getOrNull()
 * val balance = client.getBalance().getOrThrow()
 * val balance = client.getBalance().getOrElse { Amount.ZERO }
 * ```
 */
sealed class NwcResult<out T> {
    /**
     * Successful result containing the value.
     */
    data class Success<T>(val value: T) : NwcResult<T>()

    /**
     * Failed result containing the error.
     */
    data class Failure(val error: NwcError) : NwcResult<Nothing>()

    /**
     * Returns true if this is a [Success].
     */
    val isSuccess: Boolean get() = this is Success

    /**
     * Returns true if this is a [Failure].
     */
    val isFailure: Boolean get() = this is Failure

    /**
     * Returns true if this is a [Failure] with a [NwcError.Timeout] error.
     */
    val isTimeout: Boolean get() = this is Failure && error is NwcError.Timeout

    /**
     * Returns true if this is a [Failure] with a [NwcError.PaymentPending] error.
     */
    val isPaymentPending: Boolean get() = this is Failure && error is NwcError.PaymentPending

    /**
     * Returns the value if successful, or null if failed.
     */
    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }

    /**
     * Returns the error if failed, or null if successful.
     */
    fun errorOrNull(): NwcError? = when (this) {
        is Success -> null
        is Failure -> error
    }

    /**
     * Returns the value if successful, or throws [NwcException] if failed.
     */
    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw NwcException(error)
    }

    /**
     * Returns the value if successful, or the default value if failed.
     */
    fun getOrDefault(default: @UnsafeVariance T): T = when (this) {
        is Success -> value
        is Failure -> default
    }

    /**
     * Returns the value if successful, or the result of [block] if failed.
     */
    inline fun getOrElse(block: (NwcError) -> @UnsafeVariance T): T = when (this) {
        is Success -> value
        is Failure -> block(error)
    }

    /**
     * Transforms the value if successful.
     */
    inline fun <R> map(transform: (T) -> R): NwcResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    /**
     * Transforms the value if successful, allowing for another result.
     */
    inline fun <R> flatMap(transform: (T) -> NwcResult<R>): NwcResult<R> = when (this) {
        is Success -> transform(value)
        is Failure -> this
    }

    /**
     * Transforms the error if failed.
     */
    inline fun mapError(transform: (NwcError) -> NwcError): NwcResult<T> = when (this) {
        is Success -> this
        is Failure -> Failure(transform(error))
    }

    /**
     * Executes [block] if successful.
     */
    inline fun onSuccess(block: (T) -> Unit): NwcResult<T> {
        if (this is Success) block(value)
        return this
    }

    /**
     * Executes [block] if failed.
     */
    inline fun onFailure(block: (NwcError) -> Unit): NwcResult<T> {
        if (this is Failure) block(error)
        return this
    }

    companion object {
        /**
         * Creates a successful result.
         */
        fun <T> success(value: T): NwcResult<T> = Success(value)

        /**
         * Creates a failed result.
         */
        fun <T> failure(error: NwcError): NwcResult<T> = Failure(error)

        /**
         * Wraps a block in a result, catching exceptions.
         */
        inline fun <T> catch(block: () -> T): NwcResult<T> = try {
            Success(block())
        } catch (e: NwcException) {
            Failure(e.error)
        } catch (e: Exception) {
            Failure(NwcError.ProtocolError(e.message ?: "Unknown error", e))
        }
    }
}

/**
 * Converts a Kotlin [Result] to an [NwcResult].
 */
fun <T> Result<T>.toNwcResult(): NwcResult<T> = fold(
    onSuccess = { NwcResult.Success(it) },
    onFailure = { e ->
        when (e) {
            is NwcException -> NwcResult.Failure(e.error)
            else -> NwcResult.Failure(NwcError.ProtocolError(e.message ?: "Unknown error", e))
        }
    }
)
