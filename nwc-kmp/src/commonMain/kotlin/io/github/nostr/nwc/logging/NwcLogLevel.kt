package io.github.nostr.nwc.logging

/**
 * Logging levels used by the NWC client library.
 *
 * The numeric [priority] increases with severity. Consumers can filter messages by providing a
 * logger implementation that honours these levels.
 */
enum class NwcLogLevel(val priority: Int) {
    TRACE(1),
    DEBUG(2),
    INFO(3),
    WARN(4),
    ERROR(5);

    companion object {
        internal val defaultMinimum: NwcLogLevel = WARN
    }
}
