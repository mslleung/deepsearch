package io.deepsearch.presentation.admin.routes

import io.deepsearch.presentation.admin.controllers.AdminCacheController
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.ktor.plugin.scope

fun Application.configureAdminCacheRoutes() {
    routing {
        route("/admin/cache") {
            get {
                val controller = call.scope.get<AdminCacheController>()
                controller.getCacheStats(call)
            }

            post("/clear") {
                val controller = call.scope.get<AdminCacheController>()
                controller.clearCache(call)
            }
        }
    }
}

