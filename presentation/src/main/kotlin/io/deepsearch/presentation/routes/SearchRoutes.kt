package io.deepsearch.presentation.routes

import io.deepsearch.presentation.controllers.SearchController
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.scope

fun Application.configureWebScrapeRoutes() {

    routing {
        route("/api") {
            post("/search") {
                val searchController = call.scope.get<SearchController>()
                searchController.searchWebsite(call)
            }
        }
    }
} 