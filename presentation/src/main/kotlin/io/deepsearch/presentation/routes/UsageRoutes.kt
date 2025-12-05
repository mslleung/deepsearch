package io.deepsearch.presentation.routes

import io.deepsearch.presentation.config.RateLimitProviders
import io.deepsearch.presentation.controllers.UsageController
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.routing.*
import org.koin.ktor.plugin.scope

fun Application.configureUsageRoutes() {
    routing {
        rateLimit(RateLimitProviders.WEB_APP) {
            authenticate("auth-jwt") {
                route("/api/usage") {
                    get("/current") {
                        val controller = call.scope.get<UsageController>()
                        controller.getCurrentUsage(call)
                    }

                    get("/stats") {
                        val controller = call.scope.get<UsageController>()
                        controller.getUsageStats(call)
                    }

                    get("/api-keys/{keyId}/stats") {
                        val controller = call.scope.get<UsageController>()
                        controller.getApiKeyUsageStats(call)
                    }

                    get("/plans") {
                        val controller = call.scope.get<UsageController>()
                        controller.getAvailablePlans(call)
                    }

                    post("/upgrade") {
                        val controller = call.scope.get<UsageController>()
                        controller.upgradePlan(call)
                    }
                }
            }
        }
    }
}


