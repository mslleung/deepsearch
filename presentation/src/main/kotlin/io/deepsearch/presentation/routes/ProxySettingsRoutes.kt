package io.deepsearch.presentation.routes

import io.deepsearch.presentation.config.RateLimitProviders
import io.deepsearch.presentation.controllers.ProxySettingsController
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.routing.*
import org.koin.ktor.plugin.scope

fun Application.configureProxySettingsRoutes() {
    routing {
        rateLimit(RateLimitProviders.WEB_APP) {
            authenticate("auth-jwt") {
                route("/api/proxy-rules") {
                    get {
                        val controller = call.scope.get<ProxySettingsController>()
                        controller.listRules(call)
                    }

                    post {
                        val controller = call.scope.get<ProxySettingsController>()
                        controller.createRule(call)
                    }

                    put("/{id}") {
                        val controller = call.scope.get<ProxySettingsController>()
                        controller.updateRule(call)
                    }

                    delete("/{id}") {
                        val controller = call.scope.get<ProxySettingsController>()
                        controller.deleteRule(call)
                    }
                }
            }
        }
    }
}

