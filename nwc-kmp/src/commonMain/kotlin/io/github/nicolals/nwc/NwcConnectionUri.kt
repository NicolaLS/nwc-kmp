package io.github.nicolals.nwc

import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.crypto.NostrSigning
import okio.ByteString
import okio.ByteString.Companion.decodeHex

/**
 * Represents a parsed NWC connection URI.
 *
 * The URI format is:
 * ```
 * nostr+walletconnect://<wallet-pubkey>?relay=<relay-url>&secret=<client-secret>&lud16=<optional>
 * ```
 *
 * ## Usage
 *
 * ```kotlin
 * val uri = NwcConnectionUri.parse("nostr+walletconnect://...")
 * if (uri != null) {
 *     println("Wallet pubkey: ${uri.walletPubkey}")
 *     println("Relays: ${uri.relays}")
 * }
 * ```
 *
 * ## Deep Links
 *
 * For handling deep links in your app, use [parseDeepLink] to handle both
 * `nostr+walletconnect://` and `nostrnwc://` schemes.
 */
data class NwcConnectionUri(
    /**
     * The raw URI string.
     */
    val raw: String,

    /**
     * The wallet service's public key.
     */
    val walletPubkey: PublicKey,

    /**
     * The client's secret key (32-byte hex-encoded).
     * Used for signing requests and decrypting responses.
     */
    val secret: ByteString,

    /**
     * The relay URLs where the wallet service listens.
     * At least one relay is required.
     */
    val relays: List<String>,

    /**
     * Optional Lightning address for the wallet.
     */
    val lud16: String? = null,
) {
    /**
     * The client's public key derived from the secret.
     * This is used as the pubkey for request events.
     */
    val clientPubkey: PublicKey by lazy {
        // Derive public key from secret using secp256k1
        // The nostr-crypto module provides this functionality
        computeClientPubkey(secret)
    }

    companion object {
        private const val SCHEME_NWC = "nostr+walletconnect://"
        private const val SCHEME_NWC_ALT = "nostr+walletconnect:"

        /**
         * Parses a NWC connection URI string.
         *
         * @param uri The URI string to parse.
         * @return The parsed [NwcConnectionUri] or null if the URI is invalid.
         */
        fun parse(uri: String): NwcConnectionUri? {
            val trimmed = uri.trim()

            // Extract pubkey from the scheme
            val pubkeyHex: String
            val queryString: String

            when {
                trimmed.startsWith(SCHEME_NWC) -> {
                    val afterScheme = trimmed.removePrefix(SCHEME_NWC)
                    val queryIndex = afterScheme.indexOf('?')
                    if (queryIndex == -1) return null
                    pubkeyHex = afterScheme.substring(0, queryIndex)
                    queryString = afterScheme.substring(queryIndex + 1)
                }
                trimmed.startsWith(SCHEME_NWC_ALT) -> {
                    val afterScheme = trimmed.removePrefix(SCHEME_NWC_ALT)
                    val queryIndex = afterScheme.indexOf('?')
                    if (queryIndex == -1) return null
                    pubkeyHex = afterScheme.substring(0, queryIndex)
                    queryString = afterScheme.substring(queryIndex + 1)
                }
                else -> return null
            }

            // Parse wallet pubkey
            val walletPubkey = try {
                PublicKey.fromHex(pubkeyHex)
            } catch (_: Exception) {
                return null
            }

            // Parse query parameters
            val params = parseQueryString(queryString)

            // Extract secret (required)
            val secretHex = params["secret"]?.firstOrNull() ?: return null
            val secret = try {
                secretHex.decodeHex()
            } catch (_: Exception) {
                return null
            }
            if (secret.size != 32) return null

            // Extract relays (at least one required)
            val relays = params["relay"]?.map { urlDecode(it) } ?: emptyList()
            if (relays.isEmpty()) return null

            // Extract optional lud16
            val lud16 = params["lud16"]?.firstOrNull()?.let { urlDecode(it) }

            return NwcConnectionUri(
                raw = trimmed,
                walletPubkey = walletPubkey,
                secret = secret,
                relays = relays,
                lud16 = lud16,
            )
        }

        /**
         * Parses a deep link URI that may be a callback from a wallet app.
         *
         * Handles the `nostrnwc://connect` callback scheme where the connection
         * string is passed as the `value` parameter.
         *
         * @param uri The deep link URI.
         * @return The parsed [NwcConnectionUri] or null if invalid.
         */
        fun parseDeepLink(uri: String): NwcConnectionUri? {
            val trimmed = uri.trim()

            // Check if it's already a connection URI
            if (trimmed.startsWith(SCHEME_NWC) || trimmed.startsWith(SCHEME_NWC_ALT)) {
                return parse(trimmed)
            }

            // Check if it's a callback URI with value parameter
            if (trimmed.startsWith("nostrnwc://")) {
                val queryIndex = trimmed.indexOf('?')
                if (queryIndex != -1) {
                    val params = parseQueryString(trimmed.substring(queryIndex + 1))
                    val value = params["value"]?.firstOrNull()
                    if (value != null) {
                        return parse(urlDecode(value))
                    }
                }
            }

            return null
        }

        /**
         * Validates if a string is a valid NWC connection URI.
         */
        fun isValid(uri: String): Boolean = parse(uri) != null

        private fun parseQueryString(query: String): Map<String, List<String>> {
            val result = mutableMapOf<String, MutableList<String>>()
            for (pair in query.split('&')) {
                val eqIndex = pair.indexOf('=')
                if (eqIndex != -1) {
                    val key = pair.substring(0, eqIndex)
                    val value = pair.substring(eqIndex + 1)
                    result.getOrPut(key) { mutableListOf() }.add(value)
                }
            }
            return result
        }

        private fun urlDecode(value: String): String {
            return buildString {
                var i = 0
                while (i < value.length) {
                    when {
                        value[i] == '%' && i + 2 < value.length -> {
                            try {
                                val hex = value.substring(i + 1, i + 3)
                                append(hex.toInt(16).toChar())
                                i += 3
                            } catch (_: Exception) {
                                append(value[i])
                                i++
                            }
                        }
                        value[i] == '+' -> {
                            append(' ')
                            i++
                        }
                        else -> {
                            append(value[i])
                            i++
                        }
                    }
                }
            }
        }
    }
}

// Internal function to compute client pubkey from secret
internal fun computeClientPubkey(secret: ByteString): PublicKey {
    return when (val result = NostrSigning.derivePublicKeyXOnly(secret)) {
        is CryptoResult.Ok -> PublicKey.fromBytes(result.value)
        is CryptoResult.Err -> throw IllegalStateException(
            "Failed to derive public key: ${result.error}"
        )
    }
}
