package io.deepsearch.domain.models.entities

enum class PlanTier {
    FREE,
    PAID
}

enum class SubscriptionPlan(
    val planName: String,
    val tier: PlanTier,
    val maxSearches: Int,
    val maxPeriodicIndexConfigs: Int,
    val priceUsd: Double,
    val priceVersion: Int
) {
    FREE("Free", PlanTier.FREE, 20, 1, 0.0, 1),
    HOBBY("Hobby", PlanTier.PAID, 200, 5, 20.0, 1),
    PRO("Pro", PlanTier.PAID, 1000, 30, 80.0, 1);

    /**
     * Stripe lookup key for this plan's price.
     * Format: deepsearch_{planname}_v{version}_monthly
     * This key is used to find or create the corresponding Stripe Price.
     */
    val stripeLookupKey: String
        get() = "deepsearch_${planName.lowercase()}_v${priceVersion}_monthly"

    companion object {
        fun fromName(name: String): SubscriptionPlan? {
            return entries.find { it.planName == name }
        }
    }
}
