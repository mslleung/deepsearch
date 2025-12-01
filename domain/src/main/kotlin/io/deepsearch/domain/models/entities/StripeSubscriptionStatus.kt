package io.deepsearch.domain.models.entities

/**
 * Represents the status of a Stripe subscription.
 * Maps to Stripe's subscription status values.
 */
enum class StripeSubscriptionStatus {
    /**
     * The subscription is active and the customer has paid.
     */
    ACTIVE,

    /**
     * The subscription is past due because payment failed.
     * Stripe will retry payment according to retry settings.
     */
    PAST_DUE,

    /**
     * The subscription has been canceled.
     */
    CANCELED,

    /**
     * The subscription is incomplete because initial payment failed.
     * Requires customer action to complete.
     */
    INCOMPLETE,

    /**
     * The subscription is in a trial period.
     */
    TRIALING,

    /**
     * The subscription is incomplete and will expire if not completed.
     */
    INCOMPLETE_EXPIRED,

    /**
     * The subscription has been paused.
     */
    PAUSED,

    /**
     * The subscription is unpaid after exhausting all payment retry attempts.
     */
    UNPAID;

    companion object {
        /**
         * Converts a Stripe subscription status string to the enum value.
         */
        fun fromStripeStatus(status: String): StripeSubscriptionStatus? {
            return when (status) {
                "active" -> ACTIVE
                "past_due" -> PAST_DUE
                "canceled" -> CANCELED
                "incomplete" -> INCOMPLETE
                "trialing" -> TRIALING
                "incomplete_expired" -> INCOMPLETE_EXPIRED
                "paused" -> PAUSED
                "unpaid" -> UNPAID
                else -> null
            }
        }
    }
}


