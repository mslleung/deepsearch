package io.deepsearch.presentation.routes

import io.deepsearch.presentation.controllers.ApiKeyController
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import org.koin.ktor.plugin.scope

fun Application.configureApiKeyRoutes() {
    routing {
        authenticate("auth-jwt") {
            route("/api/keys") {
                get {
                    val controller = call.scope.get<ApiKeyController>()
                    controller.listKeys(call)
                }

                post {
                    val controller = call.scope.get<ApiKeyController>()
                    controller.createKey(call)
                }

                delete("/{id}") {
                    val controller = call.scope.get<ApiKeyController>()
                    controller.deleteKey(call)
                }
            }
        }
    }
}

