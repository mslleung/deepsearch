package io.deepsearch.presentation.admin.routes

import io.deepsearch.presentation.admin.controllers.AdminQuerySessionController
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.ktor.plugin.scope

fun Application.configureAdminQuerySessionRoutes() {
    routing {
        route("/admin/query-sessions") {
            get("/{id}") {
                val controller = call.scope.get<AdminQuerySessionController>()
                controller.getQuerySessionById(call)
            }
        }
    }
}

