package io.deepsearch.presentation.routes

import io.deepsearch.presentation.controllers.SearchController
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Application.configureWebScrapeRoutes() {
    val searchController by inject<SearchController>()

    routing {
        route("/api") {
            post("/search") {
                searchController.searchWebsite(call)
            }
        }
    }
} 