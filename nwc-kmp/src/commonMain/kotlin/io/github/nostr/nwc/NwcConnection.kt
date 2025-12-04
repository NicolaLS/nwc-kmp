package io.github.nostr.nwc

import nostr.core.crypto.keys.PrivateKey
import nostr.core.crypto.keys.PublicKey

/**
 * Credentials for connecting to a Nostr Wallet Connect service.
 * Can be created from a URI string or constructed directly.
 */
data class NwcCredentials(
    val walletPublicKey: PublicKey,
    val relays: List<String>,
    val secretKey: PrivateKey,
    val lud16: String? = null
) {
    init {
        require(relays.isNotEmpty()) { "NWC credentials must include at least one relay" }
    }

    val walletPublicKeyHex: String get() = walletPublicKey.toString()
    val secretKeyHex: String get() = secretKey.hex

    fun toUri(includeLud16: Boolean = true): NwcUri = NwcUri(
        raw = toUriString(includeLud16),
        credentials = this
    )

    fun toUriString(includeLud16: Boolean = true): String =
        buildNwcUriString(walletPublicKeyHex, relays, secretKeyHex, if (includeLud16) lud16 else null)

    companion object {
        fun fromUri(uri: String): NwcCredentials = parseNwcUri(uri)
    }
}

/**
 * A parsed NWC URI that preserves the original raw string.
 * Use [NwcCredentials] directly if you don't need the raw URI.
 */
data class NwcUri(
    val raw: String,
    private val credentials: NwcCredentials
) {
    val walletPublicKey: PublicKey get() = credentials.walletPublicKey
    val relays: List<String> get() = credentials.relays
    val secretKey: PrivateKey get() = credentials.secretKey
    val lud16: String? get() = credentials.lud16
    val walletPublicKeyHex: String get() = credentials.walletPublicKeyHex
    val secretKeyHex: String get() = credentials.secretKeyHex

    fun toCredentials(): NwcCredentials = credentials

    fun toUriString(includeLud16: Boolean = true): String =
        credentials.toUriString(includeLud16)

    override fun toString(): String = raw

    companion object {
        fun parse(uri: String): NwcUri = parseNwcUriToNwcUri(uri)

        fun create(
            walletPublicKey: PublicKey,
            relays: List<String>,
            secretKey: PrivateKey,
            lud16: String? = null
        ): NwcUri {
            val creds = NwcCredentials(
                walletPublicKey = walletPublicKey,
                relays = normalizeRelays(relays),
                secretKey = secretKey,
                lud16 = lud16?.takeIf { it.isNotBlank() }
            )
            return NwcUri(raw = creds.toUriString(), credentials = creds)
        }
    }
}

// Parsing functions
fun parseNwcUri(uri: String): NwcCredentials = parseNwcUriToNwcUri(uri).toCredentials()

internal fun parseNwcUriToNwcUri(uri: String): NwcUri {
    val trimmed = uri.trim()
    require(trimmed.isNotEmpty()) { "NWC URI cannot be empty" }

    val (scheme, remainder) = extractScheme(trimmed)
    require(scheme.equals("nostr+walletconnect", ignoreCase = true)) {
        "Unsupported scheme '$scheme'. Expected nostr+walletconnect://"
    }

    val withoutSlashes = remainder.removePrefix("//")
    val questionIdx = withoutSlashes.indexOf('?')
    val pubkeyPart = if (questionIdx >= 0) withoutSlashes.substring(0, questionIdx) else withoutSlashes
    require(pubkeyPart.isNotBlank()) { "NWC URI missing wallet public key" }

    val walletPublicKey = PublicKey.fromHex(pubkeyPart.lowercase())
    val query = if (questionIdx >= 0) withoutSlashes.substring(questionIdx + 1) else ""
    val parameters = parseQueryString(query)

    val relays = parameters["relay"]
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }
    require(!relays.isNullOrEmpty()) { "NWC URI must include at least one relay parameter" }

    val secret = parameters["secret"]?.firstOrNull()?.trim()
    require(!secret.isNullOrEmpty()) { "NWC URI missing required secret parameter" }

    val lud16 = parameters["lud16"]?.firstOrNull()?.takeIf { it.isNotBlank() }

    val credentials = NwcCredentials(
        walletPublicKey = walletPublicKey,
        relays = normalizeRelays(relays),
        secretKey = PrivateKey.fromHex(secret),
        lud16 = lud16
    )
    return NwcUri(raw = trimmed, credentials = credentials)
}

// Helper functions
private fun extractScheme(raw: String): Pair<String, String> {
    val schemeSeparator = raw.indexOf("://")
    return if (schemeSeparator >= 0) {
        raw.substring(0, schemeSeparator) to raw.substring(schemeSeparator + 3)
    } else {
        val colonIndex = raw.indexOf(':')
        require(colonIndex > 0) { "NWC URI missing scheme" }
        raw.substring(0, colonIndex) to raw.substring(colonIndex + 1)
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
        val value = if (equalIndex < 0) "" else decodeComponent(pair.substring(equalIndex + 1))
        result.getOrPut(key) { mutableListOf() }.add(value)
    }
    return result.mapValues { it.value.toList() }
}

private fun decodeComponent(value: String): String {
    if (value.isEmpty()) return ""
    val builder = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        when (val current = value[index]) {
            '%' -> {
                require(index + 2 < value.length) { "Incomplete percent-encoding" }
                val high = value[index + 1].digitToIntOrNull(16) ?: error("Invalid percent-encoding")
                val low = value[index + 2].digitToIntOrNull(16) ?: error("Invalid percent-encoding")
                builder.append((high shl 4 or low).toChar())
                index += 3
            }
            '+' -> { builder.append(' '); index++ }
            else -> { builder.append(current); index++ }
        }
    }
    return builder.toString()
}

private fun encodeComponent(value: String): String {
    if (value.isEmpty()) return ""
    val builder = StringBuilder(value.length)
    value.forEach { char ->
        if (char.isLetterOrDigit() || char in "-._~:/") {
            builder.append(char)
        } else {
            char.toString().encodeToByteArray().forEach { byte ->
                val b = byte.toInt() and 0xFF
                builder.append('%')
                builder.append(b.toString(16).uppercase().padStart(2, '0'))
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
    require(relays.isNotEmpty()) { "NWC URI must include at least one relay" }
    val queryParts = mutableListOf<String>()
    relays.forEach { queryParts += "relay=${encodeComponent(it)}" }
    queryParts += "secret=${encodeComponent(secretKeyHex)}"
    lud16?.let { queryParts += "lud16=${encodeComponent(it)}" }
    return "nostr+walletconnect://${walletPublicKeyHex.lowercase()}?${queryParts.joinToString("&")}"
}

private fun normalizeRelays(relays: List<String>): List<String> =
    relays.map { it.trim() }.filter { it.isNotEmpty() }

// Extension functions
fun String.toNwcUri(): NwcUri = NwcUri.parse(this)
fun String.toNwcCredentials(): NwcCredentials = parseNwcUri(this)
