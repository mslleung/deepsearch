package io.deepsearch.presentation.admin.routes

import io.deepsearch.presentation.admin.controllers.AdminQuerySessionController
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.ktor.plugin.scope

fun Application.configureAdminQuerySessionRoutes() {
    routing {
        route("/admin/query-sessions") {
            get {
                val controller = call.scope.get<AdminQuerySessionController>()
                controller.getQuerySessions(call)
            }

            get("/{id}") {
                val controller = call.scope.get<AdminQuerySessionController>()
                controller.getQuerySessionById(call)
            }
            
            /**
             * Get the search flow timeline for a session, including events and cost breakdown.
             * Used by the admin UI to visualize search flow and analyze costs.
             */
            get("/{id}/timeline") {
                val controller = call.scope.get<AdminQuerySessionController>()
                controller.getTimeline(call)
            }
        }
    }
}

