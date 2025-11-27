package io.deepsearch.presentation.routes

import io.deepsearch.presentation.controllers.PeriodicIndexController
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import org.koin.ktor.plugin.scope

fun Application.configurePeriodicIndexRoutes() {
    routing {
        authenticate("auth-jwt") {
            route("/api/periodic-index") {
                get("/config") {
                    val controller = call.scope.get<PeriodicIndexController>()
                    controller.getConfig(call)
                }
                post("/config") {
                    val controller = call.scope.get<PeriodicIndexController>()
                    controller.saveConfig(call)
                }
                delete("/config") {
                    val controller = call.scope.get<PeriodicIndexController>()
                    controller.deleteConfig(call)
                }
                post("/trigger") {
                    val controller = call.scope.get<PeriodicIndexController>()
                    controller.triggerNow(call)
                }
                get("/history") {
                    val controller = call.scope.get<PeriodicIndexController>()
                    controller.getJobHistory(call)
                }
                get("/history/{jobId}/urls") {
                    val controller = call.scope.get<PeriodicIndexController>()
                    controller.getJobUrls(call)
                }
                get("/indexed-urls") {
                    val controller = call.scope.get<PeriodicIndexController>()
                    controller.getIndexedUrlsByBaseUrl(call)
                }
            }
        }
    }
}

