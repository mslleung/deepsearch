package io.deepsearch.presentation.routes

import io.deepsearch.presentation.controllers.WebScrapeController
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureWebScrapeRoutes() {
    val webScrapeController = WebScrapeController()
    
    routing {
        route("/api") {
            post("/scrape") {
                webScrapeController.scrapeWebsite(call)
            }
        }
    }
} 