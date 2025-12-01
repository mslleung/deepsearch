package io.deepsearch.presentation.routes

import io.deepsearch.presentation.controllers.PeriodicIndexJobController
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import org.koin.ktor.plugin.scope

fun Application.configurePeriodicIndexJobRoutes() {
    routing {
        authenticate("api-key") {
            route("/api") {
                route("/periodic-index/jobs") {
                    get {
                        val controller = call.scope.get<PeriodicIndexJobController>()
                        controller.list(call)
                    }
                    post("/{id}/stop") {
                        val controller = call.scope.get<PeriodicIndexJobController>()
                        controller.stop(call)
                    }
                }
            }
        }
        
        // SSE endpoint with query param auth (EventSource cannot set headers)
        route("/api") {
            route("/periodic-index/jobs") {
                sse("/{id}/stream") {
                    // API key validation handled in controller
                    val controller = call.scope.get<PeriodicIndexJobController>()
                    controller.stream(call, this)
                }
            }
        }
    }
}

