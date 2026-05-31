package io.deepsearch.presentation.admin.routes

import io.deepsearch.presentation.admin.controllers.AdminBatchPeriodicIndexJobController
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.ktor.plugin.scope

fun Application.configureAdminBatchPeriodicIndexJobRoutes() {
    routing {
        route("/admin/batch-periodic-index-jobs") {
            get {
                val controller = call.scope.get<AdminBatchPeriodicIndexJobController>()
                controller.getAllBatchJobs(call)
            }

            get("/{id}") {
                val controller = call.scope.get<AdminBatchPeriodicIndexJobController>()
                controller.getBatchJobById(call)
            }

            get("/{id}/stats") {
                val controller = call.scope.get<AdminBatchPeriodicIndexJobController>()
                controller.getBatchJobStats(call)
            }

            get("/{id}/cost") {
                val controller = call.scope.get<AdminBatchPeriodicIndexJobController>()
                controller.getBatchJobCost(call)
            }
        }
    }
}
