package io.deepsearch.presentation.routes

import io.deepsearch.presentation.config.RateLimitProviders
import io.deepsearch.presentation.controllers.AuthController
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.routing.*
import org.koin.ktor.plugin.scope

fun Application.configureAuthRoutes() {
    routing {
        route("/api/auth") {
            // Unauthenticated routes with heavy IP-based rate limiting
            rateLimit(RateLimitProviders.PUBLIC) {
                post("/register") {
                    val controller = call.scope.get<AuthController>()
                    controller.register(call)
                }

                post("/login") {
                    val controller = call.scope.get<AuthController>()
                    controller.login(call)
                }

                post("/logout") {
                    val controller = call.scope.get<AuthController>()
                    controller.logout(call)
                }

                // OAuth Google routes
                route("/oauth/google") {
                    authenticate("auth-oauth-google") {
                        get("/login") {
                            val controller = call.scope.get<AuthController>()
                            controller.initiateGoogleOAuth(call)
                        }

                        get("/callback") {
                            val controller = call.scope.get<AuthController>()
                            controller.handleGoogleCallback(call)
                        }
                    }
                }
            }

            // JWT-authenticated routes with user-based rate limiting
            rateLimit(RateLimitProviders.WEB_APP) {
                authenticate("auth-jwt") {
                    get("/me") {
                        val controller = call.scope.get<AuthController>()
                        controller.getCurrentUser(call)
                    }
                }
            }
        }
    }
}

