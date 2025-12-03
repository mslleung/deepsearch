package io.deepsearch.application.services

import com.stripe.Stripe
import com.stripe.exception.SignatureVerificationException
import com.stripe.model.Event
import com.stripe.model.Invoice
import com.stripe.model.Subscription
import com.stripe.model.checkout.Session
import com.stripe.net.Webhook
import com.stripe.param.CustomerCreateParams
import com.stripe.param.SubscriptionCreateParams
import com.stripe.param.billingportal.SessionCreateParams as PortalSessionCreateParams
import com.stripe.param.checkout.SessionCreateParams
import io.deepsearch.domain.config.StripeConfig
import io.deepsearch.domain.models.entities.PlanTier
import io.deepsearch.domain.models.entities.StripeSubscriptionStatus
import io.deepsearch.domain.models.entities.SubscriptionPlan
import io.deepsearch.domain.models.entities.UserSubscription
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.repositories.IUserRepository
import io.deepsearch.domain.repositories.IUserSubscriptionRepository
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

/**
 * Service for handling Stripe payment operations.
 */
interface IPaymentService {

    /**
     * Result of creating a Stripe checkout session.
     */
    data class CheckoutSessionResult(
        val sessionId: String,
        val checkoutUrl: String
    )

    /**
     * Result of creating a subscription intent for Stripe Elements.
     * Contains the client secret needed to confirm payment on the frontend.
     */
    data class SubscriptionIntentResult(
        val subscriptionId: String,
        val clientSecret: String
    )

    /**
     * Result of creating a customer portal session.
     */
    data class CustomerPortalResult(
        val url: String
    )

    /**
     * Creates a Stripe Checkout session for subscribing to a plan.
     *
     * @param userId The user who is subscribing
     * @param plan The subscription plan to subscribe to
     * @return The checkout session result containing the session ID and URL
     */
    suspend fun createCheckoutSession(
        userId: UserId,
        plan: SubscriptionPlan
    ): CheckoutSessionResult

    /**
     * Creates a subscription with an incomplete payment intent for Stripe Elements.
     * The frontend uses the returned client secret to render the Payment Element
     * and confirm the payment.
     *
     * @param userId The user who is subscribing
     * @param plan The subscription plan to subscribe to
     * @return The subscription intent result containing the subscription ID and client secret
     */
    suspend fun createSubscriptionIntent(
        userId: UserId,
        plan: SubscriptionPlan
    ): SubscriptionIntentResult

    /**
     * Handles a Stripe webhook event.
     *
     * @param payload The raw webhook payload
     * @param signature The Stripe signature header
     */
    suspend fun handleWebhookEvent(payload: String, signature: String)

    /**
     * Creates a Stripe Customer Portal session for managing subscriptions.
     *
     * @param userId The user requesting access to the portal
     * @param returnUrl URL to return to after leaving the portal
     * @return The portal result containing the URL
     */
    suspend fun createCustomerPortalSession(userId: UserId, returnUrl: String): CustomerPortalResult

    /**
     * Gets the Stripe publishable key for frontend usage.
     */
    fun getPublishableKey(): String

    /**
     * Result of confirming a subscription payment.
     */
    data class ConfirmSubscriptionResult(
        val success: Boolean,
        val planName: String,
        val totalAvailable: Int
    )

    /**
     * Confirms a subscription payment and creates the subscription in our database.
     * This should be called after the frontend confirms payment with Stripe.
     * It verifies the payment status with Stripe and creates the subscription synchronously.
     *
     * @param userId The user who made the payment
     * @param subscriptionId The Stripe subscription ID returned from createSubscriptionIntent
     * @return The confirmation result with the new subscription details
     */
    suspend fun confirmSubscription(userId: UserId, subscriptionId: String): ConfirmSubscriptionResult
}

@OptIn(ExperimentalTime::class)
class PaymentService(
    private val stripeConfig: StripeConfig,
    private val stripePlanSyncService: IStripePlanSyncService,
    private val userRepository: IUserRepository,
    private val userSubscriptionRepository: IUserSubscriptionRepository
) : IPaymentService {

    private val logger = LoggerFactory.getLogger(PaymentService::class.java)

    init {
        Stripe.apiKey = stripeConfig.secretKey
    }

    override suspend fun createCheckoutSession(
        userId: UserId,
        plan: SubscriptionPlan
    ): IPaymentService.CheckoutSessionResult {
        val user = userRepository.findById(userId)
            ?: throw IllegalArgumentException("User not found: $userId")

        val stripeCustomerId = getOrCreateStripeCustomer(user)
        val priceId = stripePlanSyncService.getStripePriceId(plan)

        val frontendBaseUrl = stripeConfig.frontendUrl.trimEnd('/')
        val successUrl = "$frontendBaseUrl/dashboard/usage?payment=success&session_id={CHECKOUT_SESSION_ID}"
        val cancelUrl = "$frontendBaseUrl/dashboard/usage?payment=cancelled"

        val params = SessionCreateParams.builder()
            .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
            .setCustomer(stripeCustomerId)
            .setSuccessUrl(successUrl)
            .setCancelUrl(cancelUrl)
            .addLineItem(
                SessionCreateParams.LineItem.builder()
                    .setPrice(priceId)
                    .setQuantity(1L)
                    .build()
            )
            .putMetadata("user_id", userId.value.toString())
            .putMetadata("plan_name", plan.planName)
            .putMetadata("price_version", plan.priceVersion.toString())
            .build()

        val session = Session.create(params)

        logger.info("Created checkout session {} for user {} plan {}", session.id, userId, plan.planName)

        return IPaymentService.CheckoutSessionResult(
            sessionId = session.id,
            checkoutUrl = session.url
        )
    }

    override suspend fun createSubscriptionIntent(
        userId: UserId,
        plan: SubscriptionPlan
    ): IPaymentService.SubscriptionIntentResult {
        val user = userRepository.findById(userId)
            ?: throw IllegalArgumentException("User not found: $userId")

        val stripeCustomerId = getOrCreateStripeCustomer(user)
        val priceId = stripePlanSyncService.getStripePriceId(plan)

        // Create subscription with incomplete payment for Stripe Elements
        val params = SubscriptionCreateParams.builder()
            .setCustomer(stripeCustomerId)
            .addItem(
                SubscriptionCreateParams.Item.builder()
                    .setPrice(priceId)
                    .build()
            )
            .setPaymentBehavior(SubscriptionCreateParams.PaymentBehavior.DEFAULT_INCOMPLETE)
            .setPaymentSettings(
                SubscriptionCreateParams.PaymentSettings.builder()
                    .setSaveDefaultPaymentMethod(
                        SubscriptionCreateParams.PaymentSettings.SaveDefaultPaymentMethod.ON_SUBSCRIPTION
                    )
                    .addPaymentMethodType(
                        SubscriptionCreateParams.PaymentSettings.PaymentMethodType.CARD
                    )
                    .build()
            )
            .addExpand("latest_invoice.payment_intent")
            .putMetadata("user_id", userId.value.toString())
            .putMetadata("plan_name", plan.planName)
            .putMetadata("price_version", plan.priceVersion.toString())
            .build()

        val subscription = Subscription.create(params)

        // Extract the client secret - Stripe may not link PaymentIntent to invoice immediately,
        // so we find it by searching recent PaymentIntents for the customer
        val clientSecret = extractClientSecret(subscription, stripeCustomerId)
            ?: throw IllegalStateException("Could not extract client secret from subscription")

        logger.info("Created subscription intent {} for user {} plan {}", subscription.id, userId, plan.planName)

        return IPaymentService.SubscriptionIntentResult(
            subscriptionId = subscription.id,
            clientSecret = clientSecret
        )
    }

    override suspend fun handleWebhookEvent(payload: String, signature: String) {
        val event: Event
        try {
            event = Webhook.constructEvent(payload, signature, stripeConfig.webhookSecret)
        } catch (e: SignatureVerificationException) {
            logger.error("Invalid webhook signature", e)
            throw IllegalArgumentException("Invalid webhook signature")
        }

        logger.info("Processing webhook event: {} ({})", event.type, event.id)

        when (event.type) {
            "checkout.session.completed" -> handleCheckoutSessionCompleted(event)
            "invoice.paid" -> handleInvoicePaid(event)
            "invoice.payment_failed" -> handleInvoicePaymentFailed(event)
            "customer.subscription.updated" -> handleSubscriptionUpdated(event)
            "customer.subscription.deleted" -> handleSubscriptionDeleted(event)
            else -> logger.debug("Unhandled webhook event type: {}", event.type)
        }
    }

    override suspend fun createCustomerPortalSession(
        userId: UserId,
        returnUrl: String
    ): IPaymentService.CustomerPortalResult {
        val user = userRepository.findById(userId)
            ?: throw IllegalArgumentException("User not found: $userId")

        val stripeCustomerId = user.stripeCustomerId
            ?: throw IllegalStateException("User has no Stripe customer ID")

        val params = PortalSessionCreateParams.builder()
            .setCustomer(stripeCustomerId)
            .setReturnUrl(returnUrl)
            .build()

        val portalSession = com.stripe.model.billingportal.Session.create(params)

        return IPaymentService.CustomerPortalResult(url = portalSession.url)
    }

    override fun getPublishableKey(): String = stripeConfig.publishableKey

    override suspend fun confirmSubscription(
        userId: UserId,
        subscriptionId: String
    ): IPaymentService.ConfirmSubscriptionResult {
        // Retrieve the subscription from Stripe to verify its status
        val stripeSubscription = Subscription.retrieve(subscriptionId)

        // Verify the subscription belongs to this user
        val subscriptionUserId = stripeSubscription.metadata["user_id"]?.toIntOrNull()
        if (subscriptionUserId != userId.value) {
            throw IllegalArgumentException("Subscription does not belong to this user")
        }

        // Check if subscription is active (payment succeeded)
        if (stripeSubscription.status != "active") {
            throw IllegalStateException("Subscription payment not completed. Status: ${stripeSubscription.status}")
        }

        // Check if we already have this subscription in our database
        val existingSubscription = userSubscriptionRepository.findByStripeSubscriptionId(subscriptionId)
        if (existingSubscription != null) {
            // Already exists (maybe webhook was faster), just return success
            return IPaymentService.ConfirmSubscriptionResult(
                success = true,
                planName = existingSubscription.planName,
                totalAvailable = existingSubscription.maxSearches + existingSubscription.rolloverSearches
            )
        }

        // Create the subscription in our database
        val planName = stripeSubscription.metadata["plan_name"]
            ?: throw IllegalStateException("Missing plan_name in subscription metadata")

        val plan = SubscriptionPlan.fromName(planName)
            ?: throw IllegalStateException("Unknown plan name: $planName")

        // Cancel old subscriptions and calculate rollover
        val rolloverSearches = cancelOldSubscriptionsAndGetRollover(userId)

        val now = Clock.System.now()
        val expiryDate = if (plan.tier == PlanTier.FREE) null else now + 30.days

        val subscription = UserSubscription.fromPlan(
            userId = userId,
            plan = plan,
            startDate = now,
            expiryDate = expiryDate,
            stripeSubscriptionId = subscriptionId,
            stripePriceId = stripeSubscription.metadata["stripe_price_id"],
            stripeStatus = StripeSubscriptionStatus.ACTIVE,
            rolloverSearches = rolloverSearches
        )

        userSubscriptionRepository.save(subscription)

        logger.info(
            "Confirmed subscription for user {} plan {} with {} rollover searches",
            userId,
            planName,
            rolloverSearches
        )

        return IPaymentService.ConfirmSubscriptionResult(
            success = true,
            planName = planName,
            totalAvailable = plan.maxSearches + rolloverSearches
        )
    }

    // ==================== Private Helper Methods ====================

    /**
     * Cancels a Stripe subscription via the Stripe API.
     * This is a fire-and-forget operation - if it fails, we log but don't throw.
     */
    private fun cancelStripeSubscription(stripeSubscriptionId: String) {
        try {
            val subscription = Subscription.retrieve(stripeSubscriptionId)
            subscription.cancel()
            logger.info("Cancelled Stripe subscription: {}", stripeSubscriptionId)
        } catch (e: Exception) {
            logger.warn("Failed to cancel Stripe subscription {}: {}", stripeSubscriptionId, e.message)
        }
    }

    /**
     * Cancels all active paid subscriptions for a user and calculates the rollover amount.
     * 
     * @param userId The user whose old subscriptions should be cancelled
     * @return The total number of remaining searches to roll over to the new subscription
     */
    private suspend fun cancelOldSubscriptionsAndGetRollover(userId: UserId): Int {
        val allSubscriptions = userSubscriptionRepository.findByUserId(userId)
        
        // Find active paid subscriptions (not expired and not already cancelled)
        val activePaidSubscriptions = allSubscriptions.filter { subscription ->
            subscription.tier == PlanTier.PAID &&
            !subscription.isExpired() &&
            subscription.stripeStatus != StripeSubscriptionStatus.CANCELED
        }
        
        if (activePaidSubscriptions.isEmpty()) {
            return 0
        }
        
        var totalRollover = 0
        val now = Clock.System.now()
        
        for (subscription in activePaidSubscriptions) {
            // Calculate remaining searches
            val remaining = subscription.getRemainingSearches()
            totalRollover += remaining
            
            logger.info(
                "Subscription {} has {} remaining searches to roll over",
                subscription.id,
                remaining
            )
            
            // Cancel in Stripe if it has a Stripe subscription ID
            subscription.stripeSubscriptionId?.let { stripeSubId ->
                cancelStripeSubscription(stripeSubId)
            }
            
            // Mark as cancelled in our database
            subscription.stripeStatus = StripeSubscriptionStatus.CANCELED
            subscription.updatedAt = now
            userSubscriptionRepository.update(subscription)
        }
        
        logger.info(
            "Cancelled {} old subscriptions for user {}, total rollover: {} searches",
            activePaidSubscriptions.size,
            userId,
            totalRollover
        )
        
        return totalRollover
    }

    private suspend fun getOrCreateStripeCustomer(user: io.deepsearch.domain.models.entities.User): String {
        user.stripeCustomerId?.let { return it }

        val params = CustomerCreateParams.builder()
            .setEmail(user.email.value)
            .putMetadata("user_id", user.id!!.value.toString())
            .build()

        val customer = com.stripe.model.Customer.create(params)

        user.stripeCustomerId = customer.id
        user.updatedAt = Clock.System.now()
        userRepository.update(user)

        logger.info("Created Stripe customer {} for user {}", customer.id, user.id)

        return customer.id
    }

    /**
     * Extracts the client secret from a subscription.
     * 
     * Note: Stripe may create the PaymentIntent asynchronously and not link it to the invoice
     * in the subscription response. We handle this by searching for recent PaymentIntents
     * for the customer with status 'requires_payment_method'.
     */
    private fun extractClientSecret(subscription: Subscription, customerId: String): String? {
        // First, try to get from the expanded subscription response
        val rawJson = subscription.rawJsonObject
        if (rawJson != null) {
            val latestInvoice = rawJson.get("latest_invoice")
            if (latestInvoice != null && !latestInvoice.isJsonNull && latestInvoice.isJsonObject) {
                val paymentIntent = latestInvoice.asJsonObject.get("payment_intent")
                if (paymentIntent != null && !paymentIntent.isJsonNull && paymentIntent.isJsonObject) {
                    val clientSecret = paymentIntent.asJsonObject.get("client_secret")
                    if (clientSecret != null && !clientSecret.isJsonNull) {
                        return clientSecret.asString
                    }
                }
            }
        }

        // Fallback: Find the most recent PaymentIntent for this customer
        return findRecentPaymentIntent(customerId)
    }

    /**
     * Finds the most recent PaymentIntent with status 'requires_payment_method' for a customer.
     */
    private fun findRecentPaymentIntent(customerId: String): String? {
        return try {
            val params = com.stripe.param.PaymentIntentListParams.builder()
                .setCustomer(customerId)
                .setLimit(10)
                .build()

            val paymentIntents = com.stripe.model.PaymentIntent.list(params)

            for (pi in paymentIntents.data) {
                if (pi.status == "requires_payment_method" && !pi.clientSecret.isNullOrBlank()) {
                    logger.debug("Found PaymentIntent {} for customer {}", pi.id, customerId)
                    return pi.clientSecret
                }
            }

            logger.warn("No suitable PaymentIntent found for customer {}", customerId)
            null
        } catch (e: Exception) {
            logger.error("Failed to search PaymentIntents for customer {}: {}", customerId, e.message)
            null
        }
    }

    // ==================== Webhook Handlers ====================

    private suspend fun handleCheckoutSessionCompleted(event: Event) {
        val session = event.dataObjectDeserializer.`object`.orElse(null) as? Session
            ?: return logger.error("Could not deserialize checkout session from event")

        val userId = session.metadata["user_id"]?.toIntOrNull()
            ?: return logger.error("Missing user_id in checkout session metadata")

        val planName = session.metadata["plan_name"]
            ?: return logger.error("Missing plan_name in checkout session metadata")

        val stripeSubscriptionId = session.subscription
            ?: return logger.error("Missing subscription ID in checkout session")

        val plan = SubscriptionPlan.fromName(planName)
            ?: return logger.error("Unknown plan name: {}", planName)

        val userIdValue = UserId(userId)

        // Cancel old subscriptions and calculate rollover before creating new one
        val rolloverSearches = cancelOldSubscriptionsAndGetRollover(userIdValue)

        val now = Clock.System.now()
        val expiryDate = if (plan.tier == PlanTier.FREE) null else now + 30.days

        val subscription = UserSubscription.fromPlan(
            userId = userIdValue,
            plan = plan,
            startDate = now,
            expiryDate = expiryDate,
            stripeSubscriptionId = stripeSubscriptionId,
            stripePriceId = session.metadata["stripe_price_id"],
            stripeStatus = StripeSubscriptionStatus.ACTIVE,
            rolloverSearches = rolloverSearches
        )

        userSubscriptionRepository.save(subscription)

        logger.info(
            "Created subscription for user {} plan {} via checkout with {} rollover searches",
            userId,
            planName,
            rolloverSearches
        )
    }

    private suspend fun handleInvoicePaid(event: Event) {
        val invoice = event.dataObjectDeserializer.`object`.orElse(null) as? Invoice
            ?: return logger.error("Could not deserialize invoice from event")

        val stripeSubscriptionId = extractSubscriptionId(invoice)
            ?: return logger.debug("Invoice without subscription, skipping")

        val existingSubscription = userSubscriptionRepository.findByStripeSubscriptionId(stripeSubscriptionId)

        if (existingSubscription != null) {
            if (existingSubscription.stripeStatus != StripeSubscriptionStatus.ACTIVE) {
                existingSubscription.stripeStatus = StripeSubscriptionStatus.ACTIVE
                existingSubscription.updatedAt = Clock.System.now()
                userSubscriptionRepository.update(existingSubscription)
                logger.info("Updated subscription {} to ACTIVE after invoice paid", existingSubscription.id)
            }
        } else {
            // Subscription doesn't exist - create it (Stripe Elements flow)
            createSubscriptionFromStripe(stripeSubscriptionId)
        }
    }

    private suspend fun handleInvoicePaymentFailed(event: Event) {
        val invoice = event.dataObjectDeserializer.`object`.orElse(null) as? Invoice
            ?: return logger.error("Could not deserialize invoice from event")

        val stripeSubscriptionId = extractSubscriptionId(invoice)
            ?: return logger.debug("Invoice without subscription, skipping")

        val subscription = userSubscriptionRepository.findByStripeSubscriptionId(stripeSubscriptionId)
            ?: return logger.warn("Subscription not found for Stripe subscription ID: {}", stripeSubscriptionId)

        subscription.stripeStatus = StripeSubscriptionStatus.PAST_DUE
        subscription.updatedAt = Clock.System.now()
        userSubscriptionRepository.update(subscription)

        logger.warn("Subscription {} marked as PAST_DUE due to payment failure", subscription.id)
    }

    private suspend fun handleSubscriptionUpdated(event: Event) {
        val stripeSubscription = event.dataObjectDeserializer.`object`.orElse(null) as? Subscription
            ?: return logger.error("Could not deserialize subscription from event")

        var subscription = userSubscriptionRepository.findByStripeSubscriptionId(stripeSubscription.id)

        // If subscription doesn't exist in our database, create it (Stripe Elements flow)
        if (subscription == null) {
            logger.info("Subscription not found in DB for {}, creating from Stripe data", stripeSubscription.id)
            createSubscriptionFromStripe(stripeSubscription.id)
            subscription = userSubscriptionRepository.findByStripeSubscriptionId(stripeSubscription.id)
            if (subscription == null) {
                logger.error("Failed to create subscription for Stripe subscription ID: {}", stripeSubscription.id)
                return
            }
        }

        val newStatus = StripeSubscriptionStatus.fromStripeStatus(stripeSubscription.status)
        if (newStatus != null && newStatus != subscription.stripeStatus) {
            subscription.stripeStatus = newStatus
            subscription.updatedAt = Clock.System.now()
            userSubscriptionRepository.update(subscription)
            logger.info("Updated subscription {} status to {}", subscription.id, newStatus)
        }
    }

    private suspend fun handleSubscriptionDeleted(event: Event) {
        val stripeSubscription = event.dataObjectDeserializer.`object`.orElse(null) as? Subscription
            ?: return logger.error("Could not deserialize subscription from event")

        val subscription = userSubscriptionRepository.findByStripeSubscriptionId(stripeSubscription.id)
            ?: return logger.warn("Subscription not found for Stripe subscription ID: {}", stripeSubscription.id)

        subscription.stripeStatus = StripeSubscriptionStatus.CANCELED
        subscription.updatedAt = Clock.System.now()
        userSubscriptionRepository.update(subscription)

        logger.info("Subscription {} marked as CANCELED", subscription.id)
    }

    /**
     * Creates a subscription record in our database from a Stripe subscription.
     * This also handles rollover by cancelling old subscriptions and carrying forward remaining searches.
     */
    private suspend fun createSubscriptionFromStripe(stripeSubscriptionId: String) {
        val stripeSubscription = Subscription.retrieve(stripeSubscriptionId)

        val userId = stripeSubscription.metadata["user_id"]?.toIntOrNull()
        if (userId == null) {
            logger.warn("Cannot create subscription: missing user_id in metadata for {}", stripeSubscriptionId)
            return
        }

        val planName = stripeSubscription.metadata["plan_name"]
        if (planName == null) {
            logger.warn("Cannot create subscription: missing plan_name in metadata for {}", stripeSubscriptionId)
            return
        }

        val plan = SubscriptionPlan.fromName(planName)
        if (plan == null) {
            logger.error("Unknown plan name in subscription metadata: {}", planName)
            return
        }

        // Check if subscription already exists (race condition protection)
        if (userSubscriptionRepository.findByStripeSubscriptionId(stripeSubscriptionId) != null) {
            logger.debug("Subscription already exists for {}", stripeSubscriptionId)
            return
        }

        val userIdValue = UserId(userId)

        // Cancel old subscriptions and calculate rollover before creating new one
        val rolloverSearches = cancelOldSubscriptionsAndGetRollover(userIdValue)

        val now = Clock.System.now()
        val expiryDate = if (plan.tier == PlanTier.FREE) null else now + 30.days

        val subscription = UserSubscription.fromPlan(
            userId = userIdValue,
            plan = plan,
            startDate = now,
            expiryDate = expiryDate,
            stripeSubscriptionId = stripeSubscriptionId,
            stripePriceId = stripeSubscription.metadata["stripe_price_id"],
            stripeStatus = StripeSubscriptionStatus.ACTIVE,
            rolloverSearches = rolloverSearches
        )

        userSubscriptionRepository.save(subscription)
        logger.info(
            "Created subscription for user {} plan {} via Elements flow with {} rollover searches",
            userId,
            planName,
            rolloverSearches
        )
    }

    /**
     * Extracts subscription ID from an Invoice.
     */
    private fun extractSubscriptionId(invoice: Invoice): String? {
        val rawJson = invoice.rawJsonObject ?: return null

        return try {
            // Try direct 'subscription' field
            val subscription = rawJson.get("subscription")
            if (subscription != null && !subscription.isJsonNull) {
                return if (subscription.isJsonPrimitive) {
                    subscription.asString
                } else if (subscription.isJsonObject) {
                    subscription.asJsonObject.get("id")?.takeIf { !it.isJsonNull }?.asString
                } else null
            }

            // Try 'parent.subscription_details' field (newer Stripe API versions)
            val parent = rawJson.get("parent")
            if (parent != null && !parent.isJsonNull && parent.isJsonObject) {
                val subscriptionDetails = parent.asJsonObject.get("subscription_details")?.asJsonObject
                val parentSubscription = subscriptionDetails?.get("subscription")
                if (parentSubscription != null && !parentSubscription.isJsonNull) {
                    return if (parentSubscription.isJsonPrimitive) {
                        parentSubscription.asString
                    } else if (parentSubscription.isJsonObject) {
                        parentSubscription.asJsonObject.get("id")?.takeIf { !it.isJsonNull }?.asString
                    } else null
                }
            }

            null
        } catch (e: Exception) {
            logger.debug("Could not extract subscription ID from invoice: {}", e.message)
            null
        }
    }
}
