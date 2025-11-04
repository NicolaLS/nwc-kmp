package io.github.nostr.nwc.logging

/**
 * Pluggable logger contract used by the library. Supply an implementation through [NwcLog] to
 * forward messages to your application's logging framework.
 */
fun interface NwcLogger {
    /**
     * Logs a message for [level] and [tag].
     *
     * The [message] lambda is evaluated lazily to avoid unnecessary string allocation when the
     * selected logger drops the message. Implementations should invoke it at most once.
     */
    fun log(level: NwcLogLevel, tag: String, throwable: Throwable?, message: () -> String)

    companion object {
        val Noop: NwcLogger = NwcLogger { _, _, _, _ -> }
    }
}
