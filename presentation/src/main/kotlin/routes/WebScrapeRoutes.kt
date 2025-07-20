package io.deepsearch.presentation.routes

import io.deepsearch.presentation.controllers.WebScrapeController
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Application.configureWebScrapeRoutes() {
    val webScrapeController by inject<WebScrapeController>()

    routing {
        route("/api") {
            post("/scrape") {
                webScrapeController.scrapeWebsite(call)
            }
        }
    }
} 