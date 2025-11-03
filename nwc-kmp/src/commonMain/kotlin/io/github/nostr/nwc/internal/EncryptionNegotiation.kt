package io.github.nostr.nwc.internal

import io.github.nostr.nwc.NwcEncryptionException
import io.github.nostr.nwc.model.EncryptionScheme
import io.github.nostr.nwc.model.WalletMetadata

internal fun selectPreferredEncryption(
    metadata: WalletMetadata,
    supportedOrder: List<EncryptionScheme>
): EncryptionScheme {
    val knownSchemes = metadata.encryptionSchemes.filterNot { it is EncryptionScheme.Unknown }.toSet()
    val candidates = when {
        knownSchemes.isNotEmpty() -> knownSchemes
        metadata.encryptionDefaultedToNip04 -> setOf(EncryptionScheme.Nip04)
        else -> emptySet()
    }
    if (candidates.isEmpty()) {
        throw NwcEncryptionException("Wallet does not advertise a supported encryption scheme.")
    }
    return supportedOrder.firstOrNull { it in candidates } ?: candidates.first()
}
