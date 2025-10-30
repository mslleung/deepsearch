package io.deepsearch.presentation.admin.routes

import io.deepsearch.presentation.admin.controllers.AdminUsageController
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.ktor.plugin.scope

fun Application.configureAdminUsageRoutes() {
    routing {
        route("/admin/usage") {
            get {
                val controller = call.scope.get<AdminUsageController>()
                controller.getAggregateUsageStats(call)
            }

            get("/users/{userId}") {
                val controller = call.scope.get<AdminUsageController>()
                controller.getUserUsageStats(call)
            }
        }
    }
}

