package io.github.nostr.nwc

import nostr.core.crypto.keys.PrivateKey
import nostr.core.crypto.keys.PublicKey

data class NwcCredentials(
    val walletPublicKey: PublicKey,
    val relays: List<String>,
    val secretKey: PrivateKey,
    val lud16: String?
) {
    init {
        require(relays.isNotEmpty()) { "Nostr Wallet Connect URI must include at least one relay" }
    }
}

fun parseNwcUri(uri: String): NwcCredentials {
    val trimmed = uri.trim()
    require(trimmed.isNotEmpty()) { "Nostr Wallet Connect URI cannot be empty" }
    val (scheme, remainder) = extractScheme(trimmed)
    require(scheme.equals("nostr+walletconnect", ignoreCase = true)) {
        "Unsupported scheme '$scheme'. Expected nostr+walletconnect://"
    }
    val withoutSlashes = remainder.removePrefix("//")
    val questionIdx = withoutSlashes.indexOf('?')
    val pubkeyPart = if (questionIdx >= 0) {
        withoutSlashes.substring(0, questionIdx)
    } else {
        withoutSlashes
    }
    require(pubkeyPart.isNotBlank()) { "Nostr Wallet Connect URI missing wallet public key" }
    val walletPublicKey = PublicKey.fromHex(pubkeyPart.lowercase())
    val query = if (questionIdx >= 0) withoutSlashes.substring(questionIdx + 1) else ""
    val parameters = parseQueryString(query)
    val relays = parameters["relay"]
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }
    require(!relays.isNullOrEmpty()) {
        "Nostr Wallet Connect URI must include at least one relay parameter"
    }
    val secret = parameters["secret"]?.firstOrNull()?.trim()
    require(!secret.isNullOrEmpty()) { "Nostr Wallet Connect URI missing required secret parameter" }
    val relayList = relays!!
    val secretValue = secret!!
    val lud16 = parameters["lud16"]?.firstOrNull()?.takeIf { it.isNotBlank() }
    return NwcCredentials(
        walletPublicKey = walletPublicKey,
        relays = relayList,
        secretKey = PrivateKey.fromHex(secretValue),
        lud16 = lud16
    )
}

private fun extractScheme(raw: String): Pair<String, String> {
    val schemeSeparator = raw.indexOf("://")
    return if (schemeSeparator >= 0) {
        val scheme = raw.substring(0, schemeSeparator)
        val remainder = raw.substring(schemeSeparator + 3)
        scheme to remainder
    } else {
        val colonIndex = raw.indexOf(':')
        require(colonIndex > 0) { "Nostr Wallet Connect URI missing scheme" }
        val scheme = raw.substring(0, colonIndex)
        val remainder = raw.substring(colonIndex + 1)
        scheme to remainder
    }
}

private fun parseQueryString(raw: String): Map<String, List<String>> {
    if (raw.isBlank()) return emptyMap()
    val result = mutableMapOf<String, MutableList<String>>()
    raw.split('&').forEach { pair ->
        if (pair.isEmpty()) return@forEach
        val equalIndex = pair.indexOf('=')
        val key = when {
            equalIndex < 0 -> decodeComponent(pair)
            equalIndex == 0 -> ""
            else -> decodeComponent(pair.substring(0, equalIndex))
        }
        val value = if (equalIndex < 0) {
            ""
        } else {
            decodeComponent(pair.substring(equalIndex + 1))
        }
        result.getOrPut(key) { mutableListOf() }.add(value)
    }
    return result.mapValues { it.value.toList() }
}

private fun decodeComponent(value: String): String {
    if (value.isEmpty()) return ""
    val builder = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val current = value[index]
        when (current) {
            '%' -> {
                require(index + 2 < value.length) { "Incomplete percent-encoding" }
                val high = value[index + 1].digitToIntOrNull(16)
                    ?: error("Invalid percent-encoding")
                val low = value[index + 2].digitToIntOrNull(16)
                    ?: error("Invalid percent-encoding")
                builder.append((high shl 4 or low).toChar())
                index += 3
            }

            '+' -> {
                builder.append(' ')
                index += 1
            }

            else -> {
                builder.append(current)
                index += 1
            }
        }
    }
    return builder.toString()
}
