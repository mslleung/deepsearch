package io.deepsearch.presentation.routes

import io.deepsearch.presentation.config.RateLimitProviders
import io.deepsearch.presentation.controllers.PeriodicIndexController
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.routing.*
import org.koin.ktor.plugin.scope

fun Application.configurePeriodicIndexRoutes() {
    routing {
        rateLimit(RateLimitProviders.API_KEY) {
            authenticate("api-key") {
                route("/api/periodic-index") {
                    // Config management
                    route("/configs") {
                        get {
                            val controller = call.scope.get<PeriodicIndexController>()
                            controller.listConfigs(call)
                        }
                        post {
                            val controller = call.scope.get<PeriodicIndexController>()
                            controller.createConfig(call)
                        }
                        route("/{id}") {
                            get {
                                val controller = call.scope.get<PeriodicIndexController>()
                                controller.getConfigById(call)
                            }
                            put {
                                val controller = call.scope.get<PeriodicIndexController>()
                                controller.updateConfig(call)
                            }
                            delete {
                                val controller = call.scope.get<PeriodicIndexController>()
                                controller.deleteConfig(call)
                            }
                            put("/enable") {
                                val controller = call.scope.get<PeriodicIndexController>()
                                controller.enableConfig(call)
                            }
                            put("/disable") {
                                val controller = call.scope.get<PeriodicIndexController>()
                                controller.disableConfig(call)
                            }
                            post("/trigger") {
                                val controller = call.scope.get<PeriodicIndexController>()
                                controller.triggerConfig(call)
                            }
                            get("/history") {
                                val controller = call.scope.get<PeriodicIndexController>()
                                controller.getConfigJobHistory(call)
                            }
                        }
                    }

                    // Global history (all configs)
                    get("/history") {
                        val controller = call.scope.get<PeriodicIndexController>()
                        controller.getGlobalJobHistory(call)
                    }
                    get("/history/{jobId}/urls") {
                        val controller = call.scope.get<PeriodicIndexController>()
                        controller.getJobUrls(call)
                    }
                    get("/indexed-urls") {
                        val controller = call.scope.get<PeriodicIndexController>()
                        controller.getIndexedUrlsByBaseUrl(call)
                    }
                }
            }
        }
    }
}
