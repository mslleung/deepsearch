package io.deepsearch.presentation.routes

import io.deepsearch.presentation.controllers.PrecacheController
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import org.koin.ktor.plugin.scope

fun Application.configurePrecacheRoutes() {

    routing {
        route("/api") {
            route("/precache/jobs") {
                post {
                    val controller = call.scope.get<PrecacheController>()
                    controller.start(call)
                }
                get {
                    val controller = call.scope.get<PrecacheController>()
                    controller.list(call)
                }
                sse("/{id}/stream") {
                    val controller = call.scope.get<PrecacheController>()
                    controller.stream(call, this)
                }
                post("/{id}/stop") {
                    val controller = call.scope.get<PrecacheController>()
                    controller.stop(call)
                }
            }
        }
    }
}
