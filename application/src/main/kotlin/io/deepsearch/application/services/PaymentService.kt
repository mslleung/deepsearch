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
import com.stripe.param.SubscriptionRetrieveParams
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
            // Configure payment settings for embedded payment collection (Stripe Elements)
            .setPaymentSettings(
                SubscriptionCreateParams.PaymentSettings.builder()
                    // Save the payment method for future invoices
                    .setSaveDefaultPaymentMethod(
                        SubscriptionCreateParams.PaymentSettings.SaveDefaultPaymentMethod.ON_SUBSCRIPTION
                    )
                    // Specify payment method types to force PaymentIntent creation
                    .addPaymentMethodType(
                        SubscriptionCreateParams.PaymentSettings.PaymentMethodType.CARD
                    )
                    .build()
            )
            // Expand the latest invoice to get the payment intent client secret
            .addExpand("latest_invoice.payment_intent")
            // Expand pending_setup_intent for trial, $0 invoice, or setup-first flows
            .addExpand("pending_setup_intent")
            // Add metadata for webhook handlers
            .putMetadata("user_id", userId.value.toString())
            .putMetadata("plan_name", plan.planName)
            .putMetadata("price_version", plan.priceVersion.toString())
            .build()

        val subscription = Subscription.create(params)

        // Extract the client secret from the expanded payment intent via raw JSON
        var clientSecret = extractClientSecretFromSubscription(subscription)

        if (clientSecret == null) {
            // Retry logic to handle race conditions where PaymentIntent is created asynchronously
            // This can happen if the invoice is finalized but the PaymentIntent creation lags behind
            val maxRetries = 5
            for (i in 1..maxRetries) {
                logger.warn("Client secret not found, retrying attempt $i/$maxRetries in 2s...")
                kotlinx.coroutines.delay(2000)

                try {
                    // Refresh subscription to get latest state
                    val refreshedSubscription = Subscription.retrieve(
                        subscription.id,
                        SubscriptionRetrieveParams.builder()
                            .addExpand("latest_invoice.payment_intent")
                            .addExpand("pending_setup_intent")
                            .build(),
                        null
                    )

                    clientSecret = extractClientSecretFromSubscription(refreshedSubscription)
                    if (clientSecret != null) {
                        logger.info("Found client secret after retry $i")
                        break
                    }
                } catch (e: Exception) {
                    logger.error("Error while retrying subscription retrieval: ${e.message}")
                }
            }
        }

        if (clientSecret == null) {
            throw IllegalStateException("Could not extract client secret from subscription")
        }

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

        var subscription = userSubscriptionRepository.findByStripeSubscriptionId(stripeSubscription.id)
        
        // If subscription doesn't exist in our database, create it
        // This happens when using Stripe Elements flow - the subscription is created in Stripe
        // but only saved to our DB when payment succeeds
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
     * Checks both 'subscription' field and 'parent.subscription' field.
     */
    private fun extractSubscriptionId(invoice: Invoice): String? {
        val rawJson = invoice.rawJsonObject ?: return null
        
        return try {
            // Try direct 'subscription' field first
            val subscription = rawJson.get("subscription")
            if (subscription != null && !subscription.isJsonNull) {
                val subId = if (subscription.isJsonPrimitive) {
                    subscription.asString
                } else if (subscription.isJsonObject) {
                    subscription.asJsonObject.get("id")?.takeIf { !it.isJsonNull }?.asString
                } else null
                
                if (subId != null) {
                    logger.debug("Extracted subscription ID from 'subscription' field: {}", subId)
                    return subId
                }
            }
            
            // Try 'parent' field (newer Stripe API versions)
            val parent = rawJson.get("parent")
            if (parent != null && !parent.isJsonNull && parent.isJsonObject) {
                val parentObj = parent.asJsonObject
                val parentSubscription = parentObj.get("subscription_details")?.asJsonObject
                    ?.get("subscription")
                if (parentSubscription != null && !parentSubscription.isJsonNull) {
                    val subId = if (parentSubscription.isJsonPrimitive) {
                        parentSubscription.asString
                    } else if (parentSubscription.isJsonObject) {
                        parentSubscription.asJsonObject.get("id")?.takeIf { !it.isJsonNull }?.asString
                    } else null
                    
                    if (subId != null) {
                        logger.debug("Extracted subscription ID from 'parent.subscription_details' field: {}", subId)
                        return subId
                    }
                }
            }
            
            // Log available fields for debugging
            val keys = rawJson.keySet()
            logger.debug("Invoice {} has no subscription field. Available keys: {}", 
                rawJson.get("id")?.asString ?: "unknown", keys)
            null
        } catch (e: Exception) {
            logger.debug("Could not extract subscription ID from invoice: {}", e.message)
            null
        }
    }

    /**
     * Extracts the client secret from a subscription with expanded latest_invoice.payment_intent
     * or pending_setup_intent.
     * 
     * The subscription must be created with expansions:
     * - "latest_invoice.payment_intent" (for immediate payment flow)
     * - "pending_setup_intent" (for trial, $0 invoice, or setup-first flows)
     */
    private fun extractClientSecretFromSubscription(subscription: Subscription): String? {
        val rawJson = subscription.rawJsonObject
        if (rawJson == null) {
            logger.error("Subscription rawJsonObject is null")
            return null
        }

        // Log detailed invoice structure for debugging
        val latestInvoice = rawJson.get("latest_invoice")
        logInvoiceStructure(latestInvoice, logger)

        val result = extractClientSecretFromJson(rawJson)
        if (result.clientSecret != null) {
            if (result.source == ClientSecretSource.PENDING_SETUP_INTENT) {
                logger.info("Using client_secret from pending_setup_intent")
            }
            return result.clientSecret
        }

        logger.error(
            "Could not find client_secret. latest_invoice={}, pending_setup_intent={}, {}",
            result.latestInvoiceType,
            result.pendingSetupIntentType,
            result.paymentIntentDiagnostic ?: "no_payment_intent_diagnostic"
        )
        
        // If payment_intent wasn't expanded but we have its ID, fetch it explicitly
        val paymentIntentId = extractPaymentIntentId(rawJson)
        if (paymentIntentId != null) {
            logger.info("Attempting to fetch payment_intent {} directly", paymentIntentId)
            return fetchClientSecretFromPaymentIntent(paymentIntentId)
        }
        
        // Last resort: If latest_invoice is an object with an ID, try to retrieve and get payment_intent
        val invoiceId = extractInvoiceId(rawJson)
        if (invoiceId != null) {
            logger.info("Attempting to retrieve invoice {} and its payment_intent directly", invoiceId)
            // Try to wait a bit before fetching, as it might be async
            return fetchClientSecretFromInvoice(invoiceId)
        }
        
        return null
    }
    
    /**
     * Extracts the invoice ID from the subscription JSON.
     */
    private fun extractInvoiceId(rawJson: com.google.gson.JsonObject): String? {
        val latestInvoice = rawJson.get("latest_invoice")
        if (latestInvoice == null || latestInvoice.isJsonNull) return null
        
        // If it's a string, it's the ID directly
        if (latestInvoice.isJsonPrimitive) {
            return latestInvoice.asString
        }
        
        // If it's an object, get the id field
        if (latestInvoice.isJsonObject) {
            return latestInvoice.asJsonObject.get("id")?.takeIf { !it.isJsonNull }?.asString
        }
        
        return null
    }
    
    /**
     * Fetches the client_secret by retrieving the invoice and then its payment_intent.
     * If the invoice doesn't have a payment_intent, tries to find one by listing recent PaymentIntents.
     */
    private fun fetchClientSecretFromInvoice(invoiceId: String): String? {
        return try {
            // Retrieve the invoice with payment_intent expanded
            val params = com.stripe.param.InvoiceRetrieveParams.builder()
                .addExpand("payment_intent")
                .build()
            val invoice = Invoice.retrieve(invoiceId, params, null)
            
            // Log invoice debug info
            logger.info("Retrieved invoice {}: status={}, collection_method={}, auto_advance={}", 
                invoice.id, invoice.status, invoice.collectionMethod, invoice.autoAdvance)
            
            // Use rawJsonObject to extract the payment_intent
            val rawJson = invoice.rawJsonObject
            if (rawJson != null) {
                val paymentIntent = rawJson.get("payment_intent")
                if (paymentIntent != null && !paymentIntent.isJsonNull) {
                    if (paymentIntent.isJsonObject) {
                        val clientSecret = paymentIntent.asJsonObject.get("client_secret")
                        if (clientSecret != null && !clientSecret.isJsonNull) {
                            logger.info("Successfully fetched client_secret from invoice {} payment_intent", invoiceId)
                            return clientSecret.asString
                        }
                        val status = paymentIntent.asJsonObject.get("status")?.takeIf { !it.isJsonNull }?.asString
                        logger.error("Invoice {} payment_intent has no client_secret (status={})", invoiceId, status)
                    } else if (paymentIntent.isJsonPrimitive) {
                        // payment_intent is a string ID - fetch it directly
                        val piId = paymentIntent.asString
                        logger.info("Fetching payment_intent {} from invoice {}", piId, invoiceId)
                        return fetchClientSecretFromPaymentIntent(piId)
                    }
                } else {
                    logger.warn("Invoice {} has no payment_intent field, searching by metadata", invoiceId)
                    // Try to find PaymentIntent by searching with invoice metadata
                    val customerId = rawJson.get("customer")?.takeIf { !it.isJsonNull }?.asString
                    if (customerId != null) {
                        return findPaymentIntentForInvoice(invoiceId, customerId)
                    }
                }
            }
            
            null
        } catch (e: Exception) {
            logger.error("Failed to retrieve invoice {}: {}", invoiceId, e.message)
            null
        }
    }
    
    /**
     * Finds a PaymentIntent associated with an invoice by searching recent PaymentIntents for the customer.
     * Falls back to finding the most recent requires_payment_method PaymentIntent if no exact match.
     */
    private fun findPaymentIntentForInvoice(invoiceId: String, customerId: String): String? {
        return try {
            logger.info("Searching for PaymentIntent for invoice {} customer {}", invoiceId, customerId)
            
            val params = com.stripe.param.PaymentIntentListParams.builder()
                .setCustomer(customerId)
                .setLimit(10)
                .build()
            
            val paymentIntents = com.stripe.model.PaymentIntent.list(params)
            
            // First, try to find exact match by invoice ID
            for (pi in paymentIntents.data) {
                val piRawJson = pi.rawJsonObject
                val piInvoice = piRawJson?.get("invoice")?.takeIf { !it.isJsonNull }
                val piInvoiceId = when {
                    piInvoice == null -> null
                    piInvoice.isJsonPrimitive -> piInvoice.asString
                    piInvoice.isJsonObject -> piInvoice.asJsonObject.get("id")?.takeIf { !it.isJsonNull }?.asString
                    else -> null
                }
                
                if (piInvoiceId == invoiceId) {
                    val clientSecret = pi.clientSecret
                    if (!clientSecret.isNullOrBlank()) {
                        logger.info("Found PaymentIntent {} for invoice {}", pi.id, invoiceId)
                        return clientSecret
                    }
                }
            }
            
            // Fallback: Find the most recent PaymentIntent with status 'requires_payment_method'
            // This handles cases where Stripe doesn't link the PI to the invoice
            logger.info("No exact invoice match, looking for recent requires_payment_method PaymentIntent")
            for (pi in paymentIntents.data) {
                val status = pi.status
                val clientSecret = pi.clientSecret
                logger.debug("PaymentIntent {}: status={}, has_client_secret={}", 
                    pi.id, status, !clientSecret.isNullOrBlank())
                
                if (status == "requires_payment_method" && !clientSecret.isNullOrBlank()) {
                    logger.info("Using most recent requires_payment_method PaymentIntent {} for customer {}", 
                        pi.id, customerId)
                    return clientSecret
                }
            }
            
            logger.error("Could not find suitable PaymentIntent for invoice {} among {} recent PaymentIntents", 
                invoiceId, paymentIntents.data.size)
            null
        } catch (e: Exception) {
            logger.error("Failed to search PaymentIntents for invoice {}: {}", invoiceId, e.message)
            null
        }
    }
    
    /**
     * Extracts the payment_intent ID from the subscription JSON.
     * Returns null if not found or if it's already expanded (object).
     */
    private fun extractPaymentIntentId(rawJson: com.google.gson.JsonObject): String? {
        val latestInvoice = rawJson.get("latest_invoice")
        if (latestInvoice == null || latestInvoice.isJsonNull || !latestInvoice.isJsonObject) {
            return null
        }
        
        val paymentIntent = latestInvoice.asJsonObject.get("payment_intent")
        // If payment_intent is a string, it's the ID (not expanded)
        return if (paymentIntent != null && paymentIntent.isJsonPrimitive) {
            paymentIntent.asString
        } else {
            null
        }
    }
    
    /**
     * Fetches the client_secret directly from a PaymentIntent by ID.
     */
    private fun fetchClientSecretFromPaymentIntent(paymentIntentId: String): String? {
        return try {
            val paymentIntent = com.stripe.model.PaymentIntent.retrieve(paymentIntentId)
            val clientSecret = paymentIntent.clientSecret
            if (!clientSecret.isNullOrBlank()) {
                logger.info("Successfully fetched client_secret from payment_intent {}", paymentIntentId)
                clientSecret
            } else {
                logger.error("PaymentIntent {} has no client_secret (status={})", paymentIntentId, paymentIntent.status)
                null
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch payment_intent {}: {}", paymentIntentId, e.message)
            null
        }
    }

    companion object {
        /**
         * Result of extracting client secret from subscription JSON.
         */
        data class ClientSecretExtractionResult(
            val clientSecret: String?,
            val source: ClientSecretSource?,
            val latestInvoiceType: String,
            val pendingSetupIntentType: String,
            val paymentIntentDiagnostic: String? = null
        )

        enum class ClientSecretSource {
            LATEST_INVOICE_PAYMENT_INTENT,
            PENDING_SETUP_INTENT
        }

        /**
         * Extracts the client secret from subscription JSON.
         * This method is internal for testing purposes.
         * 
         * @param rawJson The raw JSON object from a Stripe Subscription
         * @return Extraction result with client secret (if found) and diagnostic info
         */
        internal fun extractClientSecretFromJson(rawJson: com.google.gson.JsonObject): ClientSecretExtractionResult {
            var paymentIntentDiagnostic: String? = null
            
            // Try payment_intent from latest_invoice first (standard payment flow)
            val latestInvoice = rawJson.get("latest_invoice")
            if (latestInvoice != null && !latestInvoice.isJsonNull && latestInvoice.isJsonObject) {
                val invoiceObj = latestInvoice.asJsonObject
                val paymentIntent = invoiceObj.get("payment_intent")
                
                // Build detailed diagnostic for payment_intent
                paymentIntentDiagnostic = buildPaymentIntentDiagnostic(paymentIntent)
                
                if (paymentIntent != null && !paymentIntent.isJsonNull && paymentIntent.isJsonObject) {
                    val clientSecret = paymentIntent.asJsonObject.get("client_secret")
                    if (clientSecret != null && !clientSecret.isJsonNull) {
                        return ClientSecretExtractionResult(
                            clientSecret = clientSecret.asString,
                            source = ClientSecretSource.LATEST_INVOICE_PAYMENT_INTENT,
                            latestInvoiceType = "object",
                            pendingSetupIntentType = describeJsonElement(rawJson.get("pending_setup_intent")),
                            paymentIntentDiagnostic = paymentIntentDiagnostic
                        )
                    }
                }
            }

            // Fall back to pending_setup_intent (trial, $0 invoice, or setup-first flows)
            val pendingSetupIntent = rawJson.get("pending_setup_intent")
            if (pendingSetupIntent != null && !pendingSetupIntent.isJsonNull && pendingSetupIntent.isJsonObject) {
                val clientSecret = pendingSetupIntent.asJsonObject.get("client_secret")
                if (clientSecret != null && !clientSecret.isJsonNull) {
                    return ClientSecretExtractionResult(
                        clientSecret = clientSecret.asString,
                        source = ClientSecretSource.PENDING_SETUP_INTENT,
                        latestInvoiceType = describeJsonElement(latestInvoice),
                        pendingSetupIntentType = "object",
                        paymentIntentDiagnostic = paymentIntentDiagnostic
                    )
                }
            }

            return ClientSecretExtractionResult(
                clientSecret = null,
                source = null,
                latestInvoiceType = describeJsonElement(latestInvoice),
                pendingSetupIntentType = describeJsonElement(pendingSetupIntent),
                paymentIntentDiagnostic = paymentIntentDiagnostic
            )
        }

        /**
         * Describes a JSON element for diagnostic purposes.
         */
        private fun describeJsonElement(element: com.google.gson.JsonElement?): String {
            return element?.let {
                when {
                    it.isJsonNull -> "null"
                    it.isJsonObject -> "object"
                    it.isJsonPrimitive -> "string(${it.asString})"
                    it.isJsonArray -> "array"
                    else -> "unknown"
                }
            } ?: "absent"
        }

        /**
         * Builds a diagnostic string for the payment_intent field.
         */
        private fun buildPaymentIntentDiagnostic(paymentIntent: com.google.gson.JsonElement?): String {
            if (paymentIntent == null) return "payment_intent=absent"
            if (paymentIntent.isJsonNull) return "payment_intent=null"
            if (paymentIntent.isJsonPrimitive) {
                // payment_intent is a string ID - means it wasn't expanded
                return "payment_intent=string(${paymentIntent.asString}) [NOT EXPANDED]"
            }
            if (!paymentIntent.isJsonObject) return "payment_intent=unknown_type"
            
            // It's an object - check what's inside
            val piObj = paymentIntent.asJsonObject
            val id = piObj.get("id")?.takeIf { !it.isJsonNull }?.asString ?: "no_id"
            val status = piObj.get("status")?.takeIf { !it.isJsonNull }?.asString ?: "no_status"
            val clientSecret = piObj.get("client_secret")
            val clientSecretDesc = when {
                clientSecret == null -> "absent"
                clientSecret.isJsonNull -> "null"
                clientSecret.isJsonPrimitive -> "present(${clientSecret.asString.take(20)}...)"
                else -> "unexpected_type"
            }
            return "payment_intent=object(id=$id, status=$status, client_secret=$clientSecretDesc)"
        }

        /**
         * Logs the structure of the latest_invoice for debugging.
         */
        internal fun logInvoiceStructure(latestInvoice: com.google.gson.JsonElement?, logger: org.slf4j.Logger) {
            if (latestInvoice == null || latestInvoice.isJsonNull || !latestInvoice.isJsonObject) {
                logger.debug("latest_invoice is not an object: {}", describeJsonElement(latestInvoice))
                return
            }
            
            val invoiceObj = latestInvoice.asJsonObject
            val keys = invoiceObj.keySet().sorted()
            logger.debug("latest_invoice keys: {}", keys.joinToString(", "))
            
            // Log specific important fields
            val id = invoiceObj.get("id")?.takeIf { !it.isJsonNull }?.asString
            val status = invoiceObj.get("status")?.takeIf { !it.isJsonNull }?.asString
            val paymentIntent = invoiceObj.get("payment_intent")
            val amountDue = invoiceObj.get("amount_due")?.takeIf { !it.isJsonNull }
            
            logger.debug(
                "latest_invoice details: id={}, status={}, amount_due={}, payment_intent={}",
                id, status, amountDue, describeJsonElement(paymentIntent)
            )
        }
    }
}
