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

        // Create or retrieve Stripe customer
        val stripeCustomerId = getOrCreateStripeCustomer(user)

        // Get the Stripe Price ID for this plan
        val priceId = stripePlanSyncService.getStripePriceId(plan)

        // Generate redirect URLs from configured frontend URL
        val frontendBaseUrl = stripeConfig.frontendUrl.trimEnd('/')
        val successUrl = "$frontendBaseUrl/dashboard/usage?payment=success&session_id={CHECKOUT_SESSION_ID}"
        val cancelUrl = "$frontendBaseUrl/dashboard/usage?payment=cancelled"

        // Create checkout session
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

        // Create or retrieve Stripe customer
        val stripeCustomerId = getOrCreateStripeCustomer(user)

        // Get the Stripe Price ID for this plan
        val priceId = stripePlanSyncService.getStripePriceId(plan)

        // Create subscription with incomplete payment for Stripe Elements
        val params = SubscriptionCreateParams.builder()
            .setCustomer(stripeCustomerId)
            .addItem(
                SubscriptionCreateParams.Item.builder()
                    .setPrice(priceId)
                    .build()
            )
            // Create subscription but don't activate until payment succeeds
            .setPaymentBehavior(SubscriptionCreateParams.PaymentBehavior.DEFAULT_INCOMPLETE)
            // Save the payment method for future invoices
            .setPaymentSettings(
                SubscriptionCreateParams.PaymentSettings.builder()
                    .setSaveDefaultPaymentMethod(
                        SubscriptionCreateParams.PaymentSettings.SaveDefaultPaymentMethod.ON_SUBSCRIPTION
                    )
                    .build()
            )
            // Expand the latest invoice to get the payment intent client secret
            .addExpand("latest_invoice.payment_intent")
            // Add metadata for webhook handlers
            .putMetadata("user_id", userId.value.toString())
            .putMetadata("plan_name", plan.planName)
            .putMetadata("price_version", plan.priceVersion.toString())
            .build()

        val subscription = Subscription.create(params)

        // Extract the client secret from the expanded payment intent via raw JSON
        val clientSecret = extractClientSecretFromSubscription(subscription)
            ?: throw IllegalStateException("Could not extract client secret from subscription")

        logger.info(
            "Created subscription intent {} for user {} plan {}",
            subscription.id, userId, plan.planName
        )

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

    private suspend fun getOrCreateStripeCustomer(user: io.deepsearch.domain.models.entities.User): String {
        // If user already has a Stripe customer ID, return it
        user.stripeCustomerId?.let { return it }

        // Create a new Stripe customer
        val params = CustomerCreateParams.builder()
            .setEmail(user.email.value)
            .putMetadata("user_id", user.id!!.value.toString())
            .build()

        val customer = com.stripe.model.Customer.create(params)

        // Update user with Stripe customer ID
        user.stripeCustomerId = customer.id
        user.updatedAt = Clock.System.now()
        userRepository.update(user)

        logger.info("Created Stripe customer {} for user {}", customer.id, user.id)

        return customer.id
    }

    private suspend fun handleCheckoutSessionCompleted(event: Event) {
        val session = event.dataObjectDeserializer.`object`.orElse(null) as? Session
            ?: return logger.error("Could not deserialize checkout session from event")

        val userId = session.metadata["user_id"]?.toIntOrNull()
            ?: return logger.error("Missing user_id in checkout session metadata")

        val planName = session.metadata["plan_name"]
            ?: return logger.error("Missing plan_name in checkout session metadata")

        val priceVersion = session.metadata["price_version"]?.toIntOrNull()
            ?: return logger.error("Missing price_version in checkout session metadata")

        val stripeSubscriptionId = session.subscription
            ?: return logger.error("Missing subscription ID in checkout session")

        val plan = SubscriptionPlan.fromName(planName)
            ?: return logger.error("Unknown plan name: {}", planName)

        // Create the subscription in our database
        val now = Clock.System.now()
        val expiryDate = if (plan.tier == PlanTier.FREE) null else now + 30.days

        val subscription = UserSubscription.fromPlan(
            userId = UserId(userId),
            plan = plan,
            startDate = now,
            expiryDate = expiryDate,
            stripeSubscriptionId = stripeSubscriptionId,
            stripePriceId = session.metadata["stripe_price_id"],
            stripeStatus = StripeSubscriptionStatus.ACTIVE
        )

        userSubscriptionRepository.save(subscription)

        logger.info("Created subscription for user {} plan {} via checkout", userId, planName)
    }

    private suspend fun handleInvoicePaid(event: Event) {
        val invoice = event.dataObjectDeserializer.`object`.orElse(null) as? Invoice
            ?: return logger.error("Could not deserialize invoice from event")

        val stripeSubscriptionId = extractSubscriptionId(invoice)
            ?: return logger.debug("Invoice without subscription, skipping")

        // Find the subscription in our database
        val existingSubscription = userSubscriptionRepository.findByStripeSubscriptionId(stripeSubscriptionId)

        if (existingSubscription != null) {
            // Update status to active (in case it was past_due)
            if (existingSubscription.stripeStatus != StripeSubscriptionStatus.ACTIVE) {
                existingSubscription.stripeStatus = StripeSubscriptionStatus.ACTIVE
                existingSubscription.updatedAt = Clock.System.now()
                userSubscriptionRepository.update(existingSubscription)
                logger.info("Updated subscription {} to ACTIVE after invoice paid", existingSubscription.id)
            }
        } else {
            // Subscription doesn't exist - this happens when using Stripe Elements flow
            // (no checkout.session.completed event). Create the subscription now.
            createSubscriptionFromStripe(stripeSubscriptionId)
        }
    }

    /**
     * Creates a subscription record in our database from a Stripe subscription.
     * Used when subscription is created via Stripe Elements (no checkout session).
     */
    private suspend fun createSubscriptionFromStripe(stripeSubscriptionId: String) {
        // Fetch the full subscription from Stripe to get metadata
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

        val now = Clock.System.now()
        val expiryDate = if (plan.tier == PlanTier.FREE) null else now + 30.days

        val subscription = UserSubscription.fromPlan(
            userId = UserId(userId),
            plan = plan,
            startDate = now,
            expiryDate = expiryDate,
            stripeSubscriptionId = stripeSubscriptionId,
            stripePriceId = stripeSubscription.metadata["stripe_price_id"],
            stripeStatus = StripeSubscriptionStatus.ACTIVE
        )

        userSubscriptionRepository.save(subscription)
        logger.info("Created subscription for user {} plan {} via Elements flow", userId, planName)
    }

    private suspend fun handleInvoicePaymentFailed(event: Event) {
        val invoice = event.dataObjectDeserializer.`object`.orElse(null) as? Invoice
            ?: return logger.error("Could not deserialize invoice from event")

        val stripeSubscriptionId = extractSubscriptionId(invoice)
            ?: return logger.debug("Invoice without subscription, skipping")

        val subscription = userSubscriptionRepository.findByStripeSubscriptionId(stripeSubscriptionId)
        if (subscription == null) {
            logger.warn("Subscription not found for Stripe subscription ID: {}", stripeSubscriptionId)
            return
        }

        subscription.stripeStatus = StripeSubscriptionStatus.PAST_DUE
        subscription.updatedAt = Clock.System.now()
        userSubscriptionRepository.update(subscription)

        logger.warn("Subscription {} marked as PAST_DUE due to payment failure", subscription.id)
    }

    private suspend fun handleSubscriptionUpdated(event: Event) {
        val stripeSubscription = event.dataObjectDeserializer.`object`.orElse(null) as? Subscription
            ?: return logger.error("Could not deserialize subscription from event")

        val subscription = userSubscriptionRepository.findByStripeSubscriptionId(stripeSubscription.id)
        if (subscription == null) {
            logger.warn("Subscription not found for Stripe subscription ID: {}", stripeSubscription.id)
            return
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
        if (subscription == null) {
            logger.warn("Subscription not found for Stripe subscription ID: {}", stripeSubscription.id)
            return
        }

        subscription.stripeStatus = StripeSubscriptionStatus.CANCELED
        subscription.updatedAt = Clock.System.now()
        userSubscriptionRepository.update(subscription)

        logger.info("Subscription {} marked as CANCELED", subscription.id)
    }

    /**
     * Extracts subscription ID from an Invoice using the raw JSON object.
     * This approach is needed because Stripe SDK v31+ uses different accessor patterns.
     */
    private fun extractSubscriptionId(invoice: Invoice): String? {
        return try {
            invoice.rawJsonObject
                ?.get("subscription")
                ?.takeIf { !it.isJsonNull }
                ?.asString
        } catch (e: Exception) {
            logger.debug("Could not extract subscription ID from invoice: {}", e.message)
            null
        }
    }

    /**
     * Extracts the client secret from a subscription with expanded latest_invoice.payment_intent.
     * The subscription must be created with the expansion: "latest_invoice.payment_intent"
     */
    private fun extractClientSecretFromSubscription(subscription: Subscription): String? {
        return try {
            subscription.rawJsonObject
                ?.getAsJsonObject("latest_invoice")
                ?.getAsJsonObject("payment_intent")
                ?.get("client_secret")
                ?.takeIf { !it.isJsonNull }
                ?.asString
        } catch (e: Exception) {
            logger.error("Could not extract client secret from subscription: {}", e.message)
            null
        }
    }
}
