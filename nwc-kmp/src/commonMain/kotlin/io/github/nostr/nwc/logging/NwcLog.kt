package io.github.nostr.nwc.logging


/**
 * Central logging facade for the NWC library. Consumers can replace [logger] and [minimumLevel]
 * at runtime to redirect output.
 */
object NwcLog {
    @PublishedApi
    internal var delegate: NwcLogger = NwcLogger.Noop

    @PublishedApi
    internal var levelThreshold: NwcLogLevel = NwcLogLevel.defaultMinimum

    /**
     * Replace the active [NwcLogger]. The previous instance is returned so callers can restore it.
     */
    fun setLogger(logger: NwcLogger): NwcLogger {
        val previous = delegate
        delegate = logger
        return previous
    }

    /**
     * Adjust the minimum log level. Messages below this threshold are ignored before they reach the
     * configured [NwcLogger].
     */
    fun setMinimumLevel(level: NwcLogLevel) {
        levelThreshold = level
    }

    /**
     * Returns `true` when the provided [level] will be emitted given the current threshold.
     */
    fun isLoggable(level: NwcLogLevel): Boolean = level.priority >= levelThreshold.priority

    inline fun trace(tag: String, throwable: Throwable? = null, crossinline message: () -> String) {
        log(NwcLogLevel.TRACE, tag, throwable, message)
    }

    inline fun debug(tag: String, throwable: Throwable? = null, crossinline message: () -> String) {
        log(NwcLogLevel.DEBUG, tag, throwable, message)
    }

    inline fun info(tag: String, throwable: Throwable? = null, crossinline message: () -> String) {
        log(NwcLogLevel.INFO, tag, throwable, message)
    }

    inline fun warn(tag: String, throwable: Throwable? = null, crossinline message: () -> String) {
        log(NwcLogLevel.WARN, tag, throwable, message)
    }

    inline fun error(tag: String, throwable: Throwable? = null, crossinline message: () -> String) {
        log(NwcLogLevel.ERROR, tag, throwable, message)
    }

    inline fun log(
        level: NwcLogLevel,
        tag: String,
        throwable: Throwable? = null,
        crossinline message: () -> String
    ) {
        if (!isLoggable(level)) return
        try {
            delegate.log(level, tag, throwable) { message() }
        } catch (_: Throwable) {
            // Never let logger failures bubble back into caller code.
        }
    }
}
