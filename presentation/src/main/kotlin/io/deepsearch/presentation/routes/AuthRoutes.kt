package io.deepsearch.presentation.routes

import io.deepsearch.domain.config.JwtConfig
import io.deepsearch.presentation.controllers.AuthController
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import org.koin.ktor.plugin.scope

fun Application.configureAuthRoutes() {
    routing {
        route("/api/auth") {
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

            authenticate("auth-jwt") {
                get("/me") {
                    val controller = call.scope.get<AuthController>()
                    controller.getCurrentUser(call)
                }
            }
        }
    }
}

