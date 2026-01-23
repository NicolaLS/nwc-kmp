package io.github.nicolals.nwc

/**
 * Helper for handling NWC deep links in mobile apps.
 *
 * ## Registering Deep Link Handlers
 *
 * ### Android (Manifest)
 * ```xml
 * <intent-filter>
 *     <action android:name="android.intent.action.VIEW" />
 *     <category android:name="android.intent.category.DEFAULT" />
 *     <category android:name="android.intent.category.BROWSABLE" />
 *     <data android:scheme="nostr+walletconnect" />
 * </intent-filter>
 * ```
 *
 * ### iOS (Info.plist)
 * ```xml
 * <key>CFBundleURLTypes</key>
 * <array>
 *     <dict>
 *         <key>CFBundleURLSchemes</key>
 *         <array>
 *             <string>nostr+walletconnect</string>
 *         </array>
 *     </dict>
 * </array>
 * ```
 *
 * ## Usage
 *
 * ```kotlin
 * // In your Activity/ViewModel
 * fun handleDeepLink(uri: String) {
 *     when (val result = NwcDeepLink.parse(uri)) {
 *         is NwcDeepLink.ConnectionUri -> {
 *             // User scanned/clicked an NWC connection URI
 *             val client = NwcClient(result.uri, viewModelScope)
 *             // Navigate to connection screen
 *         }
 *         is NwcDeepLink.Callback -> {
 *             // Response from wallet app with connection string
 *             val connectionUri = NwcConnectionUri.parse(result.value)
 *             // ...
 *         }
 *         null -> {
 *             // Not a valid NWC deep link
 *         }
 *     }
 * }
 * ```
 */
sealed class NwcDeepLink {
    /**
     * A direct NWC connection URI.
     * Format: `nostr+walletconnect://<pubkey>?relay=...&secret=...`
     */
    data class ConnectionUri(val uri: NwcConnectionUri) : NwcDeepLink()

    /**
     * A callback from a wallet app containing the connection string.
     * Format: `nostrnwc://connect?value=<encoded_nwc_uri>`
     */
    data class Callback(val value: String) : NwcDeepLink()

    companion object {
        /**
         * Parses a deep link URI.
         *
         * @param uri The deep link URI string.
         * @return The parsed deep link or null if invalid.
         */
        fun parse(uri: String): NwcDeepLink? {
            val trimmed = uri.trim()

            // Check for direct connection URI
            if (trimmed.startsWith("nostr+walletconnect://") ||
                trimmed.startsWith("nostr+walletconnect:")) {
                val connectionUri = NwcConnectionUri.parse(trimmed) ?: return null
                return ConnectionUri(connectionUri)
            }

            // Check for callback URI
            if (trimmed.startsWith("nostrnwc://")) {
                val queryIndex = trimmed.indexOf('?')
                if (queryIndex != -1) {
                    val queryString = trimmed.substring(queryIndex + 1)
                    val params = parseQueryString(queryString)
                    val value = params["value"]?.firstOrNull()
                    if (value != null) {
                        val decoded = urlDecode(value)
                        // Try to parse as connection URI
                        val connectionUri = NwcConnectionUri.parse(decoded)
                        return if (connectionUri != null) {
                            ConnectionUri(connectionUri)
                        } else {
                            Callback(decoded)
                        }
                    }
                }
            }

            return null
        }

        /**
         * Checks if a URI string is a valid NWC deep link.
         */
        fun isNwcDeepLink(uri: String): Boolean {
            val trimmed = uri.trim()
            return trimmed.startsWith("nostr+walletconnect://") ||
                    trimmed.startsWith("nostr+walletconnect:") ||
                    trimmed.startsWith("nostrnwc://")
        }

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

/**
 * Builder for creating `nostrnwc://connect` deep link requests.
 *
 * Use this to request a connection from a wallet app.
 *
 * ## Usage
 *
 * ```kotlin
 * val deepLinkUri = NwcConnectRequest.Builder()
 *     .appName("My App")
 *     .appIcon("https://example.com/icon.png")
 *     .callback("myapp://nwc-callback")
 *     .build()
 *
 * // Open the URI to launch wallet app
 * val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkUri))
 * startActivity(intent)
 * ```
 */
class NwcConnectRequest private constructor(
    private val appName: String?,
    private val appIcon: String?,
    private val callback: String?,
    private val walletAppName: String?,
) {
    /**
     * Builds the deep link URI for requesting an NWC connection.
     *
     * @return The `nostrnwc://connect` URI with all parameters.
     */
    fun toUri(): String {
        val scheme = if (walletAppName != null) {
            "nostrnwc+$walletAppName://connect"
        } else {
            "nostrnwc://connect"
        }

        val params = mutableListOf<String>()
        appName?.let { params.add("appname=${urlEncode(it)}") }
        appIcon?.let { params.add("appicon=${urlEncode(it)}") }
        callback?.let { params.add("callback=${urlEncode(it)}") }

        return if (params.isEmpty()) {
            scheme
        } else {
            "$scheme?${params.joinToString("&")}"
        }
    }

    class Builder {
        private var appName: String? = null
        private var appIcon: String? = null
        private var callback: String? = null
        private var walletAppName: String? = null

        /**
         * Sets the name of your app (shown to user in wallet).
         */
        fun appName(name: String) = apply { this.appName = name }

        /**
         * Sets the icon URL for your app (shown to user in wallet).
         */
        fun appIcon(iconUrl: String) = apply { this.appIcon = iconUrl }

        /**
         * Sets the callback URI scheme that the wallet should use to return
         * the connection string.
         */
        fun callback(callbackUri: String) = apply { this.callback = callbackUri }

        /**
         * Targets a specific wallet app by name.
         * Uses `nostrnwc+{app_name}://connect` scheme.
         */
        fun walletApp(name: String) = apply { this.walletAppName = name }

        /**
         * Builds the connect request.
         */
        fun build(): NwcConnectRequest = NwcConnectRequest(appName, appIcon, callback, walletAppName)
    }

    companion object {
        private fun urlEncode(value: String): String {
            return buildString {
                for (char in value) {
                    when {
                        char.isLetterOrDigit() || char in "-_.~" -> append(char)
                        char == ' ' -> append('+')
                        else -> {
                            val bytes = char.toString().encodeToByteArray()
                            for (byte in bytes) {
                                append('%')
                                append(hexChar((byte.toInt() shr 4) and 0x0F))
                                append(hexChar(byte.toInt() and 0x0F))
                            }
                        }
                    }
                }
            }
        }

        private fun hexChar(value: Int): Char = when {
            value < 10 -> ('0' + value)
            else -> ('A' + value - 10)
        }
    }
}
