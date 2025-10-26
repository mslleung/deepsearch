package io.deepsearch.presentation.routes

import io.deepsearch.presentation.controllers.CacheController
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import org.koin.ktor.plugin.scope

fun Application.configureCacheRoutes() {
    routing {
        authenticate("api-key") {
            route("/api/cache") {
                get("/list") {
                    val controller = call.scope.get<CacheController>()
                    controller.list(call)
                }

                get("/content") {
                    val controller = call.scope.get<CacheController>()
                    controller.content(call)
                }

                get("/search") {
                    val controller = call.scope.get<CacheController>()
                    controller.search(call)
                }
            }
        }
    }
}
