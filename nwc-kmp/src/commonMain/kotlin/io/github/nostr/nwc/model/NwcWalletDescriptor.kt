package io.github.nostr.nwc.model

import io.github.nostr.nwc.NwcUri

data class NwcWalletDescriptor(
    val uri: NwcUri,
    val metadata: WalletMetadata,
    val info: GetInfoResult,
    val negotiatedEncryption: EncryptionScheme?,
    val relays: List<String>,
    val lud16: String?
) {
    val alias: String? get() = info.alias
    val color: String? get() = info.color
    val pubkey: String get() = info.pubkey
    val network: Network get() = info.network
    val blockHeight: Long? get() = info.blockHeight
    val blockHash: String? get() = info.blockHash
    val capabilities: Set<NwcCapability> = metadata.capabilities union info.methods
    val notifications: Set<NwcNotificationType> = metadata.notificationTypes union info.notifications
    val encryptionSchemes: Set<EncryptionScheme> get() = metadata.encryptionSchemes
}
