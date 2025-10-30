package io.deepsearch.presentation.admin.controllers

import io.deepsearch.domain.repositories.IWebpageMarkdownRepository
import io.deepsearch.domain.repositories.IWebpageImageRepository
import io.deepsearch.domain.repositories.IPdfMarkdownRepository
import io.deepsearch.domain.repositories.IWebpageTableRepository
import io.deepsearch.presentation.admin.dto.AdminCacheStatsDto
import io.deepsearch.presentation.admin.dto.AdminCachedUrlDto
import io.deepsearch.presentation.admin.dto.ClearCacheRequest
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class AdminCacheController(
    private val webpageMarkdownRepository: IWebpageMarkdownRepository,
    private val webpageImageRepository: IWebpageImageRepository,
    private val pdfMarkdownRepository: IPdfMarkdownRepository,
    private val webpageTableRepository: IWebpageTableRepository
) {

    suspend fun getCacheStats(call: ApplicationCall) {
        try {
            // Get counts for each cache type
            val cachedPages = webpageMarkdownRepository.countByDomainPrefix("")
            
            // Get recent cached URLs (limit to 100)
            val recentPages = webpageMarkdownRepository.listByDomainPrefix("", 0, 100)
            val recentCachedUrls = recentPages.map { page ->
                AdminCachedUrlDto(
                    url = page.url,
                    cachedAt = page.updatedAt.toEpochMilliseconds(),
                    contentType = "webpage"
                )
            }
            
            val stats = AdminCacheStatsDto(
                totalCachedPages = cachedPages.toInt(),
                totalCachedImages = 0, // TODO: Add count method if needed
                totalCachedPdfs = 0, // TODO: Add count method if needed
                totalCachedTables = 0, // TODO: Add count method if needed
                recentlyCachedUrls = recentCachedUrls
            )
            
            call.respond(HttpStatusCode.OK, stats)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    suspend fun clearCache(call: ApplicationCall) {
        try {
            val request = call.receive<ClearCacheRequest>()
            
            if (request.url != null) {
                // Clear cache for specific URL
                // Note: There's no delete method in the repository, so this is a placeholder
                call.respond(HttpStatusCode.OK, mapOf("message" to "Cache clearing not implemented yet"))
            } else {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "URL required for cache clearing"))
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }
}

