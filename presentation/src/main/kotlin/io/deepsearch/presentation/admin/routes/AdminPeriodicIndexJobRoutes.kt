package io.deepsearch.presentation.admin.routes

import io.deepsearch.presentation.admin.controllers.AdminPeriodicIndexJobController
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.ktor.plugin.scope

fun Application.configureAdminPeriodicIndexJobRoutes() {
    routing {
        route("/admin/periodic-index-jobs") {
            get {
                val controller = call.scope.get<AdminPeriodicIndexJobController>()
                controller.getAllPeriodicIndexJobs(call)
            }

            get("/{id}") {
                val controller = call.scope.get<AdminPeriodicIndexJobController>()
                controller.getPeriodicIndexJobById(call)
            }

            get("/{id}/cost") {
                val controller = call.scope.get<AdminPeriodicIndexJobController>()
                controller.getPeriodicIndexJobCost(call)
            }

            post("/{id}/stop") {
                val controller = call.scope.get<AdminPeriodicIndexJobController>()
                controller.stopPeriodicIndexJob(call)
            }
        }
    }
}

