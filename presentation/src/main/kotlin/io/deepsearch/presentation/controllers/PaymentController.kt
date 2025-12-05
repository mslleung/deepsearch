package io.deepsearch.presentation.controllers

import io.deepsearch.application.services.IPaymentService
import io.deepsearch.domain.config.JwtConfig
import io.deepsearch.domain.models.entities.SubscriptionPlan
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.presentation.dto.CreateCheckoutSessionRequest
import io.deepsearch.presentation.dto.CreateCheckoutSessionResponse
import io.deepsearch.presentation.dto.CreatePortalSessionRequest
import io.deepsearch.presentation.dto.CreatePortalSessionResponse
import io.deepsearch.presentation.dto.ConfirmSubscriptionRequest
import io.deepsearch.presentation.dto.ConfirmSubscriptionResponse
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
        val response = PaymentConfigResponse(
            publishableKey = paymentService.getPublishableKey()
        )
        call.respond(HttpStatusCode.OK, response)
    }

    /**
     * Creates a Stripe Checkout session for subscribing to a plan.
     */
    suspend fun createCheckoutSession(call: ApplicationCall) {
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
    }

    /**
     * Creates a subscription intent for Stripe Elements payment flow.
     * Returns a client secret to be used with the Payment Element on the frontend.
     */
    suspend fun createSubscriptionIntent(call: ApplicationCall) {
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
    }

    /**
     * Confirms a subscription payment after the frontend has completed the Stripe payment flow.
     * This verifies the payment with Stripe and creates the subscription in our database.
     */
    suspend fun confirmSubscription(call: ApplicationCall) {
        val userId = getUserIdFromJwt(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
            return
        }

        val request = call.receive<ConfirmSubscriptionRequest>()

        val result = paymentService.confirmSubscription(
            userId = userId,
            subscriptionId = request.subscriptionId
        )

        val response = ConfirmSubscriptionResponse(
            success = result.success,
            planName = result.planName,
            totalAvailable = result.totalAvailable
        )

        call.respond(HttpStatusCode.OK, response)
    }

    /**
     * Handles Stripe webhook events.
     * This endpoint does NOT require authentication - Stripe calls it directly.
     * Note: We keep basic validation here since Stripe expects specific responses for retries.
     */
    suspend fun handleWebhook(call: ApplicationCall) {
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
    }

    /**
     * Creates a Stripe Customer Portal session for managing subscriptions.
     */
    suspend fun createPortalSession(call: ApplicationCall) {
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
    }

    private fun getUserIdFromJwt(call: ApplicationCall): UserId? {
        val principal = call.principal<JWTPrincipal>()
        val userIdClaim = principal?.payload?.getClaim(JwtConfig.CLAIM_USER_ID)?.asInt()
        return userIdClaim?.let { UserId(it) }
    }
}
