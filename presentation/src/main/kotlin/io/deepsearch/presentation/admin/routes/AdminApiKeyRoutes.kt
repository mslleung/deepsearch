package io.deepsearch.presentation.admin.routes

import io.deepsearch.presentation.admin.controllers.AdminApiKeyController
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.ktor.plugin.scope

fun Application.configureAdminApiKeyRoutes() {
    routing {
        route("/admin/api-keys") {
            get {
                val controller = call.scope.get<AdminApiKeyController>()
                controller.getAllApiKeys(call)
            }

            get("/{id}") {
                val controller = call.scope.get<AdminApiKeyController>()
                controller.getApiKeyById(call)
            }

            delete("/{id}") {
                val controller = call.scope.get<AdminApiKeyController>()
                controller.revokeApiKey(call)
            }

            post("/{id}/restore") {
                val controller = call.scope.get<AdminApiKeyController>()
                controller.restoreApiKey(call)
            }

            get("/{id}/usage") {
                val controller = call.scope.get<AdminApiKeyController>()
                controller.getApiKeyUsageStats(call)
            }
        }
    }
}

