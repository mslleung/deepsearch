package io.deepsearch.presentation.controllers

import io.deepsearch.presentation.dto.SearchRequest
import io.deepsearch.application.services.ISearchService
import io.deepsearch.domain.exceptions.AiInterpretationException
import io.deepsearch.domain.exceptions.InvalidUrlException
import io.deepsearch.domain.exceptions.WebScrapeException
import io.deepsearch.domain.exceptions.WebScrapeTimeoutException
import io.deepsearch.presentation.dto.SearchResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*

class SearchController(private val searchService: ISearchService) {
    suspend fun searchWebsite(call: ApplicationCall) {
        try {
            val request = call.receive<SearchRequest>()
            val response = searchService.searchWebsite(request.query, request.url)
            call.respond(HttpStatusCode.OK, SearchResponse(response))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
        } catch (e: InvalidUrlException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
        } catch (e: WebScrapeTimeoutException) {
            call.respond(HttpStatusCode.RequestTimeout, mapOf("error" to e.message))
        } catch (e: WebScrapeException) {
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to e.message))
        } catch (e: AiInterpretationException) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to e.message))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }
} 