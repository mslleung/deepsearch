package io.deepsearch.presentation.controllers

import io.deepsearch.application.services.ICacheQueryService
import io.deepsearch.domain.models.entities.WebpageMarkdown
import io.deepsearch.presentation.dto.CacheContent
import io.deepsearch.presentation.dto.CacheItem
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import kotlin.time.ExperimentalTime

class CacheController(private val cacheQueryService: ICacheQueryService) {
    suspend fun list(call: ApplicationCall) {
        val domain = call.request.queryParameters["domain"]
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
        val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 10

        if (domain.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Missing 'domain' parameter")
            return
        }

        val items: List<CacheItem> = cacheQueryService
            .listByDomain(domain, page, size)
            .map { it.toItem() }
        call.respond(items)
    }

    suspend fun content(call: ApplicationCall) {
        val url = call.request.queryParameters["url"]
        if (url.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Missing 'url' parameter")
            return
        }

        val content = cacheQueryService.getContent(url)
        if (content != null) {
            call.respond(CacheContent(markdown = content.markdown))
        } else {
            call.respond(HttpStatusCode.NotFound, "Content not found for URL: $url")
        }
    }

    suspend fun search(call: ApplicationCall) {
        val query = call.request.queryParameters["query"]
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
        val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 10

        if (query.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Missing 'query' parameter")
            return
        }

        val items: List<CacheItem> = cacheQueryService
            .searchByUrl(query, page, size)
            .map { it.toItem() }
        call.respond(items)
    }

    @OptIn(ExperimentalTime::class)
    private fun WebpageMarkdown.toItem(): CacheItem = CacheItem(
        url = url,
        updatedAt = updatedAt.toEpochMilliseconds(),
        httpStatus = httpStatus,
        mimeType = mimeType
    )
}


