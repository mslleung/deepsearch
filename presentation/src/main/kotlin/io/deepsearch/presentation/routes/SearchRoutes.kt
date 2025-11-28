package io.deepsearch.presentation.routes

import io.deepsearch.presentation.controllers.SearchController
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import org.koin.ktor.plugin.scope

fun Application.configureSearchRoutes() {
    routing {
        authenticate("api-key") {
            route("/api") {
                // Blocking search endpoint - returns full result when complete
                post("/search") {
                    val searchController = call.scope.get<SearchController>()
                    searchController.searchWebsite(call)
                }
            }
        }

        // SSE streaming search endpoint (auth via query param since EventSource cannot set headers)
        // Usage: GET /api/search/stream?apiKey=...&query=...&url=...&mode=...&maxCacheAge=...
        route("/api/search") {
            sse("/stream") {
                val searchController = call.scope.get<SearchController>()
                searchController.streamSearch(call, this)
            }
        }
    }
}
