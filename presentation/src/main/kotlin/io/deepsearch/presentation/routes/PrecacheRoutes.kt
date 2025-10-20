package io.deepsearch.presentation.routes

import io.deepsearch.presentation.controllers.PrecacheController
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import org.koin.ktor.plugin.scope

fun Application.configurePrecacheRoutes() {

    routing {
        route("/api") {
            sse("/precache") {
                val controller = call.scope.get<PrecacheController>()
                controller.stream(call, this)
            }
        }
    }
}
