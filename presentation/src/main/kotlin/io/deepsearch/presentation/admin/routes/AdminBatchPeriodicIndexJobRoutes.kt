package io.deepsearch.presentation.admin.routes

import io.deepsearch.presentation.admin.controllers.AdminBatchPeriodicIndexJobController
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.ktor.plugin.scope

/**
 * Admin routes for batch periodic index jobs.
 * 
 * Endpoints:
 * - GET /admin/batch-periodic-index-jobs - List all batch jobs
 * - GET /admin/batch-periodic-index-jobs/{id} - Get batch job details
 * - GET /admin/batch-periodic-index-jobs/{id}/stats - Get batch job statistics
 * - GET /admin/batch-periodic-index-jobs/{id}/cost - Get batch job cost breakdown
 * - POST /admin/batch-periodic-index-jobs/{id}/stop - Stop a running batch job
 */
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

            post("/{id}/stop") {
                val controller = call.scope.get<AdminBatchPeriodicIndexJobController>()
                controller.stopBatchJob(call)
            }
        }
    }
}
