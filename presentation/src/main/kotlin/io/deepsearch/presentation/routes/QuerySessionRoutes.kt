package io.deepsearch.presentation.routes

import io.deepsearch.presentation.config.RateLimitProviders
import io.deepsearch.presentation.controllers.QuerySessionController
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.routing.*
import org.koin.ktor.plugin.scope

fun Application.configureQuerySessionRoutes() {
    routing {
        rateLimit(RateLimitProviders.WEB_APP) {
            authenticate("auth-jwt") {
                route("/api/query-sessions") {
                    get {
                        val controller = call.scope.get<QuerySessionController>()
                        controller.getQuerySessions(call)
                    }
                    
                    get("/analytics") {
                        val controller = call.scope.get<QuerySessionController>()
                        controller.getQuerySessionAnalytics(call)
                    }

                    get("/{id}") {
                        val controller = call.scope.get<QuerySessionController>()
                        controller.getQuerySessionDetail(call)
                    }
                }
            }
        }
    }
}

