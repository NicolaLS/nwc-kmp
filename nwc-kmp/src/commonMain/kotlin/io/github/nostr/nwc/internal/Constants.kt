package io.github.nostr.nwc.internal

internal object MethodNames {
    const val PAY_INVOICE = "pay_invoice"
    const val MULTI_PAY_INVOICE = "multi_pay_invoice"
    const val PAY_KEYSEND = "pay_keysend"
    const val MULTI_PAY_KEYSEND = "multi_pay_keysend"
    const val MAKE_INVOICE = "make_invoice"
    const val LOOKUP_INVOICE = "lookup_invoice"
    const val LIST_TRANSACTIONS = "list_transactions"
    const val GET_BALANCE = "get_balance"
    const val GET_INFO = "get_info"
}

internal object NotificationTypes {
    const val PAYMENT_RECEIVED = "payment_received"
    const val PAYMENT_SENT = "payment_sent"
}

internal const val ENCRYPTION_SCHEME_NIP44 = "nip44_v2"
internal const val ENCRYPTION_SCHEME_NIP04 = "nip04"

internal const val TAG_ENCRYPTION = "encryption"
internal const val TAG_P = "p"
internal const val TAG_E = "e"
internal const val TAG_D = "d"
internal const val TAG_EXPIRATION = "expiration"
