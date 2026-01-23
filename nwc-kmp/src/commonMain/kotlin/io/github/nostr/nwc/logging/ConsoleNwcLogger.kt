package io.github.nostr.nwc.logging

/**
 * Basic logger that prints to standard output. Intended for quick diagnostics and platforms where
 * no structured logger is available.
 */
object ConsoleNwcLogger : NwcLogger {
    override fun log(level: NwcLogLevel, tag: String, throwable: Throwable?, message: () -> String) {
        val prefix = "[${level.name}] $tag:"
        val body = message()
        if (throwable == null) {
            println("$prefix $body")
        } else {
            println("$prefix $body\n${throwable.stackTraceToStringSafe()}")
        }
    }

    private fun Throwable.stackTraceToStringSafe(): String = try {
        stackTraceToString()
    } catch (_: Throwable) {
        message ?: this::class.simpleName.orEmpty()
    }
}
