package io.deepsearch.presentation.routes

import io.deepsearch.presentation.config.RateLimitProviders
import io.deepsearch.presentation.controllers.BatchPeriodicIndexController
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.routing.*
import org.koin.ktor.plugin.scope

/**
 * Configure routes for viewing batch periodic index jobs.
 * 
 * Batch jobs are created internally by the backend scheduler for recurring
 * periodic index jobs. Users cannot trigger batch jobs directly - they use
 * the interactive method for "trigger now" which provides instant feedback.
 * 
 * These endpoints are READ-ONLY for viewing batch job progress via polling.
 * Batch jobs can take up to 24+ hours but provide 50% cost savings.
 */
fun Application.configureBatchPeriodicIndexRoutes() {
    routing {
        rateLimit(RateLimitProviders.API_KEY_GENERAL) {
            authenticate("api-key") {
                route("/api/batch-periodic-index") {
                    // List all batch jobs for the user
                    get("/jobs") {
                        val controller = call.scope.get<BatchPeriodicIndexController>()
                        controller.list(call)
                    }

                    // Get a specific batch job (for polling progress)
                    get("/jobs/{id}") {
                        val controller = call.scope.get<BatchPeriodicIndexController>()
                        controller.getById(call)
                    }

                    // Get batch job statistics (detailed progress)
                    get("/jobs/{id}/stats") {
                        val controller = call.scope.get<BatchPeriodicIndexController>()
                        controller.getStats(call)
                    }

                    // Stop a batch job (user can cancel long-running jobs)
                    post("/jobs/{id}/stop") {
                        val controller = call.scope.get<BatchPeriodicIndexController>()
                        controller.stop(call)
                    }
                }
            }
        }
    }
}

