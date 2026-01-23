package io.github.nicolals.nwc

import io.github.nicolals.nostr.nip47.event.NwcInfoEvent
import io.github.nicolals.nostr.nip47.model.NwcEncryption

/**
 * Information about a wallet from the NWC info event (kind 13194).
 *
 * This is retrieved from the relay when connecting and can be used to
 * determine what capabilities the wallet supports.
 */
data class WalletInfo(
    /**
     * Set of capabilities supported by the wallet.
     */
    val capabilities: Set<NwcCapability>,

    /**
     * Set of notification types supported by the wallet.
     */
    val notifications: Set<NwcNotificationType>,

    /**
     * Set of encryption schemes supported by the wallet.
     */
    val encryptions: Set<NwcEncryption>,

    /**
     * The preferred encryption scheme to use.
     * This will be NIP-44 if supported, otherwise NIP-04.
     */
    val preferredEncryption: NwcEncryption,

    /**
     * Whether encryption defaulted to NIP-04 because no encryption tag was present.
     * This indicates a wallet that doesn't explicitly advertise encryption support.
     */
    val encryptionDefaultedToNip04: Boolean = false,
) {
    /**
     * Whether the wallet supports a specific capability.
     */
    fun supports(capability: NwcCapability): Boolean = capability in capabilities

    /**
     * Whether the wallet supports receiving notifications.
     */
    val supportsNotifications: Boolean
        get() = notifications.isNotEmpty() && NwcCapability.NOTIFICATIONS in capabilities

    /**
     * Capabilities as string values for persistence.
     */
    val capabilityStrings: Set<String>
        get() = capabilities.map { it.value }.toSet()

    /**
     * Notifications as string values for persistence.
     */
    val notificationStrings: Set<String>
        get() = notifications.map { it.value }.toSet()

    /**
     * Encryption schemes as string tags for persistence.
     */
    val encryptionStrings: Set<String>
        get() = encryptions.map { it.tag }.toSet()

    companion object {
        /**
         * Creates WalletInfo from an NWC info event.
         */
        fun fromInfoEvent(event: NwcInfoEvent): WalletInfo {
            val capabilities = event.capabilities.mapNotNull { NwcCapability.fromValue(it) }.toSet()
            val notifications = event.notificationTypes.mapNotNull { NwcNotificationType.fromValue(it) }.toSet()
            val encryptions = event.supportedEncryptions.toSet()

            // Check if encryption defaulted (no encryption tag was present in info event)
            val encryptionDefaulted = encryptions.isEmpty()

            val preferredEncryption = when {
                NwcEncryption.NIP44_V2 in encryptions -> NwcEncryption.NIP44_V2
                NwcEncryption.NIP04 in encryptions -> NwcEncryption.NIP04
                else -> NwcEncryption.NIP04 // Default to NIP-04 if no encryption tag
            }

            // If no encryption schemes were advertised, add NIP04 as default
            val effectiveEncryptions = if (encryptionDefaulted) {
                setOf(NwcEncryption.NIP04)
            } else {
                encryptions
            }

            return WalletInfo(
                capabilities = capabilities,
                notifications = notifications,
                encryptions = effectiveEncryptions,
                preferredEncryption = preferredEncryption,
                encryptionDefaultedToNip04 = encryptionDefaulted,
            )
        }

        /**
         * Creates a minimal WalletInfo when no info event is available.
         * Defaults to NIP-04 encryption for backwards compatibility.
         */
        fun default(): WalletInfo = WalletInfo(
            capabilities = emptySet(),
            notifications = emptySet(),
            encryptions = setOf(NwcEncryption.NIP04),
            preferredEncryption = NwcEncryption.NIP04,
            encryptionDefaultedToNip04 = true,
        )
    }
}

/**
 * Detailed wallet information from `get_info` response.
 *
 * This includes node-level details that aren't available from the info event.
 */
data class WalletDetails(
    /**
     * Human-readable name for the wallet/node.
     */
    val alias: String? = null,

    /**
     * Color associated with the node (hex string).
     */
    val color: String? = null,

    /**
     * Node's public key.
     */
    val pubkey: String? = null,

    /**
     * Bitcoin network (mainnet, testnet, signet, regtest).
     */
    val network: String? = null,

    /**
     * Current block height.
     */
    val blockHeight: Long? = null,

    /**
     * Current block hash.
     */
    val blockHash: String? = null,

    /**
     * Methods supported by this connection.
     */
    val methods: Set<NwcCapability> = emptySet(),

    /**
     * Notification types supported by this connection.
     */
    val notifications: Set<NwcNotificationType> = emptySet(),
)

/**
 * NWC capabilities (methods) defined in NIP-47.
 */
enum class NwcCapability(val value: String) {
    PAY_INVOICE("pay_invoice"),
    MULTI_PAY_INVOICE("multi_pay_invoice"),
    PAY_KEYSEND("pay_keysend"),
    MULTI_PAY_KEYSEND("multi_pay_keysend"),
    MAKE_INVOICE("make_invoice"),
    LOOKUP_INVOICE("lookup_invoice"),
    LIST_TRANSACTIONS("list_transactions"),
    GET_BALANCE("get_balance"),
    GET_INFO("get_info"),
    NOTIFICATIONS("notifications");

    companion object {
        fun fromValue(value: String): NwcCapability? =
            entries.find { it.value == value }
    }
}

/**
 * NWC notification types defined in NIP-47.
 */
enum class NwcNotificationType(val value: String) {
    PAYMENT_RECEIVED("payment_received"),
    PAYMENT_SENT("payment_sent");

    companion object {
        fun fromValue(value: String): NwcNotificationType? =
            entries.find { it.value == value }
    }
}
