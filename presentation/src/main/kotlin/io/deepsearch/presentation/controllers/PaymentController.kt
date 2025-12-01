package io.deepsearch.presentation.controllers

import io.deepsearch.application.services.IPaymentService
import io.deepsearch.domain.config.JwtConfig
import io.deepsearch.domain.models.entities.SubscriptionPlan
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.presentation.dto.CreateCheckoutSessionRequest
import io.deepsearch.presentation.dto.CreateCheckoutSessionResponse
import io.deepsearch.presentation.dto.CreatePortalSessionRequest
import io.deepsearch.presentation.dto.CreatePortalSessionResponse
import io.deepsearch.presentation.dto.CreateSubscriptionIntentRequest
import io.deepsearch.presentation.dto.CreateSubscriptionIntentResponse
import io.deepsearch.presentation.dto.PaymentConfigResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class PaymentController(
    private val paymentService: IPaymentService
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Returns the Stripe publishable key for frontend initialization.
     */
    suspend fun getConfig(call: ApplicationCall) {
        try {
            val response = PaymentConfigResponse(
                publishableKey = paymentService.getPublishableKey()
            )
            call.respond(HttpStatusCode.OK, response)
        } catch (e: Exception) {
            logger.error("Error getting payment config: {}", e.message, e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    /**
     * Creates a Stripe Checkout session for subscribing to a plan.
     */
    suspend fun createCheckoutSession(call: ApplicationCall) {
        try {
            val userId = getUserIdFromJwt(call)
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                return
            }

            val request = call.receive<CreateCheckoutSessionRequest>()
            
            val plan = SubscriptionPlan.fromName(request.planName)
            if (plan == null || plan == SubscriptionPlan.FREE) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid plan name"))
                return
            }

            val result = paymentService.createCheckoutSession(
                userId = userId,
                plan = plan
            )

            val response = CreateCheckoutSessionResponse(
                sessionId = result.sessionId,
                checkoutUrl = result.checkoutUrl
            )

            call.respond(HttpStatusCode.OK, response)
        } catch (e: IllegalArgumentException) {
            logger.warn("Bad request in createCheckoutSession: {}", e.message, e)
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
        } catch (e: Exception) {
            logger.error("Error creating checkout session: {}", e.message, e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    /**
     * Creates a subscription intent for Stripe Elements payment flow.
     * Returns a client secret to be used with the Payment Element on the frontend.
     */
    suspend fun createSubscriptionIntent(call: ApplicationCall) {
        try {
            val userId = getUserIdFromJwt(call)
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                return
            }

            val request = call.receive<CreateSubscriptionIntentRequest>()

            val plan = SubscriptionPlan.fromName(request.planName)
            if (plan == null || plan == SubscriptionPlan.FREE) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid plan name"))
                return
            }

            val result = paymentService.createSubscriptionIntent(
                userId = userId,
                plan = plan
            )

            val response = CreateSubscriptionIntentResponse(
                subscriptionId = result.subscriptionId,
                clientSecret = result.clientSecret
            )

            call.respond(HttpStatusCode.OK, response)
        } catch (e: IllegalArgumentException) {
            logger.warn("Bad request in createSubscriptionIntent: {}", e.message, e)
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
        } catch (e: Exception) {
            logger.error("Error creating subscription intent: {}", e.message, e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    /**
     * Handles Stripe webhook events.
     * This endpoint does NOT require authentication - Stripe calls it directly.
     */
    suspend fun handleWebhook(call: ApplicationCall) {
        try {
            val payload = call.receiveText()
            val signature = call.request.header("Stripe-Signature")
            
            if (signature == null) {
                logger.warn("Webhook called without Stripe-Signature header")
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing Stripe-Signature header"))
                return
            }

            paymentService.handleWebhookEvent(payload, signature)

            // Return 200 OK to acknowledge receipt
            call.respond(HttpStatusCode.OK, mapOf("received" to true))
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid webhook: {}", e.message)
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
        } catch (e: Exception) {
            logger.error("Error processing webhook: {}", e.message, e)
            // Return 500 so Stripe will retry
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    /**
     * Creates a Stripe Customer Portal session for managing subscriptions.
     */
    suspend fun createPortalSession(call: ApplicationCall) {
        try {
            val userId = getUserIdFromJwt(call)
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                return
            }

            val request = call.receive<CreatePortalSessionRequest>()

            val result = paymentService.createCustomerPortalSession(
                userId = userId,
                returnUrl = request.returnUrl
            )

            val response = CreatePortalSessionResponse(url = result.url)
            call.respond(HttpStatusCode.OK, response)
        } catch (e: IllegalStateException) {
            logger.warn("Cannot create portal session: {}", e.message)
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
        } catch (e: Exception) {
            logger.error("Error creating portal session: {}", e.message, e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    private fun getUserIdFromJwt(call: ApplicationCall): UserId? {
        val principal = call.principal<JWTPrincipal>()
        val userIdClaim = principal?.payload?.getClaim(JwtConfig.CLAIM_USER_ID)?.asInt()
        return userIdClaim?.let { UserId(it) }
    }
}
