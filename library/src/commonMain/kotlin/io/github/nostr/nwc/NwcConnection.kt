package io.github.nostr.nwc

import nostr.core.crypto.keys.PrivateKey
import nostr.core.crypto.keys.PublicKey

data class NwcUri internal constructor(
    val raw: String,
    val walletPublicKey: PublicKey,
    val relays: List<String>,
    val secretKey: PrivateKey,
    val lud16: String?
) {
    init {
        require(relays.isNotEmpty()) { "Nostr Wallet Connect URI must include at least one relay" }
    }

    val walletPublicKeyHex: String get() = walletPublicKey.toString()
    val secretKeyHex: String get() = secretKey.hex

    fun toCredentials(): NwcCredentials = NwcCredentials(
        walletPublicKey = walletPublicKey,
        relays = relays,
        secretKey = secretKey,
        lud16 = lud16
    )

    fun toUriString(includeLud16: Boolean = true): String =
        buildNwcUriString(walletPublicKeyHex, relays, secretKeyHex, if (includeLud16) lud16 else null)

    override fun toString(): String = toUriString()

    companion object {
        fun parse(uri: String): NwcUri = parseNwcUriComponents(uri)

        fun create(
            walletPublicKey: PublicKey,
            relays: List<String>,
            secretKey: PrivateKey,
            lud16: String? = null
        ): NwcUri {
            val normalizedRelays = normalizeRelays(relays)
            require(normalizedRelays.isNotEmpty()) { "Nostr Wallet Connect URI must include at least one relay" }
            val sanitizedLud16 = lud16?.takeIf { it.isNotBlank() }
            val formatted = buildNwcUriString(walletPublicKey.toString(), normalizedRelays, secretKey.hex, sanitizedLud16)
            return NwcUri(
                raw = formatted,
                walletPublicKey = walletPublicKey,
                relays = normalizedRelays,
                secretKey = secretKey,
                lud16 = sanitizedLud16
            )
        }
    }
}

data class NwcCredentials(
    val walletPublicKey: PublicKey,
    val relays: List<String>,
    val secretKey: PrivateKey,
    val lud16: String?
) {
    init {
        require(relays.isNotEmpty()) { "Nostr Wallet Connect URI must include at least one relay" }
    }

    val walletPublicKeyHex: String get() = walletPublicKey.toString()
    val secretKeyHex: String get() = secretKey.hex

    fun toUri(includeLud16: Boolean = true): NwcUri =
        NwcUri.create(
            walletPublicKey = walletPublicKey,
            relays = relays,
            secretKey = secretKey,
            lud16 = if (includeLud16) lud16 else null
        )

    fun toUriString(includeLud16: Boolean = true): String =
        toUri(includeLud16).toUriString(includeLud16)
}

fun parseNwcUri(uri: String): NwcCredentials {
    return parseNwcUriComponents(uri).toCredentials()
}

internal fun parseNwcUriComponents(uri: String): NwcUri {
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
    val relayList = normalizeRelays(relays!!)
    val secretValue = secret!!.trim()
    val lud16 = parameters["lud16"]?.firstOrNull()?.takeIf { it.isNotBlank() }
    return NwcUri(
        raw = trimmed,
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

private fun encodeComponent(value: String): String {
    if (value.isEmpty()) return ""
    val builder = StringBuilder(value.length)
    value.forEach { char ->
        val keep = char.isLetterOrDigit() || char in setOf('-', '.', '_', '~', ':', '/')
        if (keep) {
            builder.append(char)
        } else {
            char.toString().encodeToByteArray().forEach { byte ->
                val b = byte.toInt() and 0xFF
                builder.append('%')
                val hex = b.toString(16).uppercase()
                if (hex.length == 1) builder.append('0')
                builder.append(hex)
            }
        }
    }
    return builder.toString()
}

private fun buildNwcUriString(
    walletPublicKeyHex: String,
    relays: List<String>,
    secretKeyHex: String,
    lud16: String?
): String {
    require(relays.isNotEmpty()) { "Nostr Wallet Connect URI must include at least one relay" }
    val queryParts = mutableListOf<String>()
    relays.forEach { relay ->
        queryParts += "relay=${encodeComponent(relay)}"
    }
    queryParts += "secret=${encodeComponent(secretKeyHex)}"
    if (lud16 != null) {
        queryParts += "lud16=${encodeComponent(lud16)}"
    }
    val query = queryParts.joinToString("&")
    return buildString {
        append("nostr+walletconnect://")
        append(walletPublicKeyHex.lowercase())
        append('?')
        append(query)
    }
}

private fun normalizeRelays(relays: List<String>): List<String> =
    relays.map { it.trim() }.filter { it.isNotEmpty() }

fun String.toNwcUri(): NwcUri = NwcUri.parse(this)

fun String.toNwcCredentials(): NwcCredentials = parseNwcUri(this)
