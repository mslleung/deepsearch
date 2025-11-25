package io.deepsearch.presentation.routes

import io.deepsearch.presentation.controllers.PeriodicIndexJobController
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import org.koin.ktor.plugin.scope

fun Application.configurePeriodicIndexJobRoutes() {
    routing {
        authenticate("auth-jwt") {
            route("/api") {
                route("/periodic-index/jobs") {
                    get {
                        val controller = call.scope.get<PeriodicIndexJobController>()
                        controller.list(call)
                    }
                    sse("/{id}/stream") {
                        val controller = call.scope.get<PeriodicIndexJobController>()
                        controller.stream(call, this)
                    }
                    post("/{id}/stop") {
                        val controller = call.scope.get<PeriodicIndexJobController>()
                        controller.stop(call)
                    }
                }
            }
        }
    }
}

