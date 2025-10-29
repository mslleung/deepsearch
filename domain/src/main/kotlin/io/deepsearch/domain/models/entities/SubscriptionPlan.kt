package io.deepsearch.domain.models.entities

enum class PlanTier {
    FREE,
    PAID
}

enum class SubscriptionPlan(
    val planName: String,
    val tier: PlanTier,
    val maxSearches: Int,
    val priceUsd: Double
) {
    FREE("Free", PlanTier.FREE, 20, 0.0),
    HOBBY("Hobby", PlanTier.PAID, 200, 20.0),
    PRO("Pro", PlanTier.PAID, 1000, 80.0);

    companion object {
        fun fromName(name: String): SubscriptionPlan? {
            return entries.find { it.planName == name }
        }
    }
}
