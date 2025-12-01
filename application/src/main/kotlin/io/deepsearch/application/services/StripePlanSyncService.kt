package io.deepsearch.application.services

import com.stripe.Stripe
import com.stripe.model.Price
import com.stripe.model.Product
import com.stripe.param.PriceCreateParams
import com.stripe.param.PriceListParams
import com.stripe.param.ProductCreateParams
import io.deepsearch.domain.config.StripeConfig
import io.deepsearch.domain.models.entities.PlanTier
import io.deepsearch.domain.models.entities.SubscriptionPlan
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Service that manages Stripe plan synchronization.
 * Maps SubscriptionPlan enum entries to Stripe Price IDs.
 */
interface IStripePlanSyncService {
    /**
     * Synchronizes all paid plans to Stripe on startup.
     * Creates Products and Prices if they don't exist.
     */
    fun syncPlansToStripe()

    /**
     * Gets the Stripe Price ID for a given plan.
     * @throws IllegalStateException if the plan hasn't been synced
     */
    fun getStripePriceId(plan: SubscriptionPlan): String
}

/**
 * Service that synchronizes SubscriptionPlan definitions to Stripe.
 * 
 * On startup, this service ensures that each paid plan has a corresponding
 * Product and Price in Stripe. It uses lookup_key for idempotent price creation.
 * 
 * Versioning strategy:
 * - Each plan has a priceVersion
 * - The lookup_key includes the version: "deepsearch_{plan}_v{version}_monthly"
 * - When you bump the version, a new Price is created
 * - Old subscribers keep paying the old price until migrated
 */
class StripePlanSyncService(
    private val stripeConfig: StripeConfig
) : IStripePlanSyncService {

    private val logger = LoggerFactory.getLogger(StripePlanSyncService::class.java)

    // Cache of plan -> Stripe Price ID
    private val planToPriceId = ConcurrentHashMap<SubscriptionPlan, String>()

    // Cache of plan -> Stripe Product ID
    private val planToProductId = ConcurrentHashMap<SubscriptionPlan, String>()

    init {
        Stripe.apiKey = stripeConfig.secretKey

        syncPlansToStripe()
    }

    override fun syncPlansToStripe() {
        logger.info("Starting Stripe plan synchronization...")

        val paidPlans = SubscriptionPlan.entries.filter { it.tier == PlanTier.PAID }

        for (plan in paidPlans) {
            try {
                syncPlanToStripe(plan)
            } catch (e: Exception) {
                logger.error("Failed to sync plan {} to Stripe: {}", plan.planName, e.message, e)
                throw e
            }
        }

        logger.info("Stripe plan synchronization complete. Synced {} plans.", paidPlans.size)
    }

    override fun getStripePriceId(plan: SubscriptionPlan): String {
        return planToPriceId[plan]
            ?: throw IllegalStateException("Plan ${plan.planName} has not been synced to Stripe. Call syncPlansToStripe() first.")
    }

    private fun syncPlanToStripe(plan: SubscriptionPlan) {
        val lookupKey = plan.stripeLookupKey
        logger.debug("Syncing plan {} with lookup_key: {}", plan.planName, lookupKey)

        // Try to find existing price by lookup_key
        val existingPrice = findPriceByLookupKey(lookupKey)

        if (existingPrice != null) {
            // Price already exists, cache it
            planToPriceId[plan] = existingPrice.id
            planToProductId[plan] = existingPrice.product
            logger.info("Found existing Stripe Price for {}: {} (Product: {})", 
                plan.planName, existingPrice.id, existingPrice.product)
            return
        }

        // Price doesn't exist, create Product and Price
        logger.info("Creating new Stripe Product and Price for plan: {}", plan.planName)

        // Create Product
        val product = createProduct(plan)
        planToProductId[plan] = product.id

        // Create Price with lookup_key
        val price = createPrice(plan, product.id)
        planToPriceId[plan] = price.id

        logger.info("Created Stripe Product {} and Price {} for plan {}", 
            product.id, price.id, plan.planName)
    }

    private fun findPriceByLookupKey(lookupKey: String): Price? {
        val params = PriceListParams.builder()
            .addAllLookupKey(listOf(lookupKey))
            .setActive(true)
            .setLimit(1L)
            .build()

        val prices = Price.list(params)
        return prices.getData().firstOrNull()
    }

    private fun createProduct(plan: SubscriptionPlan): Product {
        val params = ProductCreateParams.builder()
            .setName("DeepSearch ${plan.planName} Plan")
            .setDescription(buildProductDescription(plan))
            .putMetadata("plan_name", plan.planName)
            .putMetadata("tier", plan.tier.name)
            .build()

        return Product.create(params)
    }

    private fun createPrice(plan: SubscriptionPlan, productId: String): Price {
        val params = PriceCreateParams.builder()
            .setProduct(productId)
            .setCurrency("usd")
            .setUnitAmount((plan.priceUsd * 100).toLong()) // Stripe uses cents
            .setRecurring(
                PriceCreateParams.Recurring.builder()
                    .setInterval(PriceCreateParams.Recurring.Interval.MONTH)
                    .build()
            )
            .setLookupKey(plan.stripeLookupKey)
            .putMetadata("plan_name", plan.planName)
            .putMetadata("price_version", plan.priceVersion.toString())
            .putMetadata("max_searches", plan.maxSearches.toString())
            .build()

        return Price.create(params)
    }

    private fun buildProductDescription(plan: SubscriptionPlan): String {
        return buildString {
            append("${plan.maxSearches} high-fidelity searches per month. ")
            append("${plan.maxPeriodicIndexConfigs} periodic index configurations. ")
            append("Full API access.")
        }
    }
}


