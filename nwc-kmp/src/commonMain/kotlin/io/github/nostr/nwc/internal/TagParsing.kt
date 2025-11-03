package io.github.nostr.nwc.internal

import io.github.nostr.nwc.model.EncryptionScheme

internal data class EncryptionTagInfo(
    val schemes: List<EncryptionScheme>,
    val defaultedToNip04: Boolean
)

internal fun parseEncryptionTagValues(rawValues: List<String>?): EncryptionTagInfo {
    if (rawValues == null) {
        return EncryptionTagInfo(emptyList(), defaultedToNip04 = true)
    }
    val segments = rawValues.flatMap { value ->
        value.split(' ', '\n', '\t', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
    if (segments.isEmpty()) {
        return EncryptionTagInfo(emptyList(), defaultedToNip04 = true)
    }
    val schemes = segments.mapNotNull { EncryptionScheme.fromWire(it) }
    return EncryptionTagInfo(schemes, defaultedToNip04 = false)
}
