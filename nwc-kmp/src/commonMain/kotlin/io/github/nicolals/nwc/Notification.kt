package io.github.nicolals.nwc

/**
 * Wallet notification received from the NWC service.
 *
 * Notifications are sent by the wallet service when certain events occur,
 * such as receiving a payment or sending a payment.
 */
sealed class WalletNotification {
    /**
     * The transaction associated with this notification.
     */
    abstract val transaction: Transaction

    /**
     * Notification for a received payment.
     */
    data class PaymentReceived(
        override val transaction: Transaction,
    ) : WalletNotification()

    /**
     * Notification for a sent payment.
     */
    data class PaymentSent(
        override val transaction: Transaction,
    ) : WalletNotification()
}
