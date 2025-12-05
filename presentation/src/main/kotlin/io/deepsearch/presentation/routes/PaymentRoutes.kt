package io.deepsearch.presentation.routes

import io.deepsearch.presentation.config.RateLimitProviders
import io.deepsearch.presentation.controllers.PaymentController
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.routing.*
import org.koin.ktor.plugin.scope

fun Application.configurePaymentRoutes() {
    routing {
        // Public endpoint - returns Stripe publishable key (with IP-based rate limiting)
        rateLimit(RateLimitProviders.PUBLIC) {
            route("/api/payment") {
                get("/config") {
                    val controller = call.scope.get<PaymentController>()
                    controller.getConfig(call)
                }
            }
        }

        // Authenticated endpoints (with user-based rate limiting)
        rateLimit(RateLimitProviders.WEB_APP) {
            authenticate("auth-jwt") {
                route("/api/payment") {
                    post("/checkout") {
                        val controller = call.scope.get<PaymentController>()
                        controller.createCheckoutSession(call)
                    }

                    post("/create-subscription-intent") {
                        val controller = call.scope.get<PaymentController>()
                        controller.createSubscriptionIntent(call)
                    }

                    post("/confirm-subscription") {
                        val controller = call.scope.get<PaymentController>()
                        controller.confirmSubscription(call)
                    }

                    post("/portal") {
                        val controller = call.scope.get<PaymentController>()
                        controller.createPortalSession(call)
                    }
                }
            }
        }

        // Stripe webhook - no rate limiting (Stripe verifies via signature)
        route("/api/stripe") {
            post("/webhook") {
                val controller = call.scope.get<PaymentController>()
                controller.handleWebhook(call)
            }
        }
    }
}


