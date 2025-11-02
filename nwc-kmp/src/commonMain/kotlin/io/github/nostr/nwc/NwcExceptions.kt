package io.github.nostr.nwc

import io.github.nostr.nwc.model.NwcError

open class NwcException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class NwcRequestException(val error: NwcError) :
    NwcException("NWC request failed: ${error.code}: ${error.message}")

class NwcTimeoutException(message: String, cause: Throwable? = null) : NwcException(message, cause)

class NwcEncryptionException(message: String) : NwcException(message)
