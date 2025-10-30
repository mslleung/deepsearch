package io.deepsearch.presentation.admin.routes

import io.deepsearch.presentation.admin.controllers.AdminPrecacheController
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.ktor.plugin.scope

fun Application.configureAdminPrecacheRoutes() {
    routing {
        route("/admin/precache") {
            get {
                val controller = call.scope.get<AdminPrecacheController>()
                controller.getAllPrecacheJobs(call)
            }

            get("/{id}") {
                val controller = call.scope.get<AdminPrecacheController>()
                controller.getPrecacheJobById(call)
            }

            post("/{id}/stop") {
                val controller = call.scope.get<AdminPrecacheController>()
                controller.stopPrecacheJob(call)
            }
        }
    }
}

