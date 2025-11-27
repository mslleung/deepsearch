package io.deepsearch.application.services

import io.deepsearch.domain.browser.IBrowserRuntimePool
import io.deepsearch.domain.models.valueobjects.WebpageLink
import io.deepsearch.domain.exceptions.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import io.deepsearch.application.services.IUrlContentProcessingService.*
import io.deepsearch.domain.models.valueobjects.PeriodicIndexSessionId
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.services.INormalizeUrlService

interface IUrlContentProcessingService {
    /**
     * Events emitted during URL processing.
     * Links are discovered first (~5s), then markdown is extracted (~1min).
     * If processing fails, UrlProcessingException is thrown and should be caught using Flow.catch{}.
     */
    sealed interface UrlProcessingEvent {
        val url: String

        data class LinkDiscoveryComplete(
            override val url: String,
            val discoveredLinks: List<WebpageLink>
        ) : UrlProcessingEvent

        data class MarkdownExtractionComplete(
            override val url: String,
            val markdown: String,
            val title: String?,
            val description: String?,
            val wasCached: Boolean
        ) : UrlProcessingEvent
    }

    /**
     * Process a URL and discover links relevant to the provided query.
     * Emits LinkDiscoveryComplete event first, then MarkdownExtractionComplete event.
     */
    fun processUrlAsFlow(
        url: String,
        query: String,
        cacheExpiryMs: Long? = null,
        sessionId: QuerySessionId
    ): Flow<UrlProcessingEvent>

    /**
     * Process a URL and discover all links on the page (query-agnostic).
     * Used for periodic index jobs.
     * Emits LinkDiscoveryComplete event first, then MarkdownExtractionComplete event.
     */
    fun processUrlAsFlow(
        url: String,
        cacheExpiryMs: Long? = null,
        sessionId: PeriodicIndexSessionId
    ): Flow<UrlProcessingEvent>
}

class UrlContentProcessingService(
    private val browserRuntimePool: IBrowserRuntimePool,
    private val normalizeUrlService: INormalizeUrlService,
    private val webpageCacheService: IWebpageCacheService,
    private val httpContentTypeResolutionService: IHttpContentTypeResolutionService,
    private val webpageExtractionService: IWebpageExtractionService,
    private val webpageLinkDiscoveryService: IWebpageLinkDiscoveryService,
    private val pdfConversionService: IPdfConversionService,
    private val urlProcessingLockRegistry: UrlProcessingLockRegistry
) : IUrlContentProcessingService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override fun processUrlAsFlow(
        url: String,
        query: String,
        cacheExpiryMs: Long?,
        sessionId: QuerySessionId
    ): Flow<UrlProcessingEvent> {
        return processInternalAsFlow(url, cacheExpiryMs, sessionId) { html ->
            webpageLinkDiscoveryService.discoverRelevantLinksByAgent(query, html, url, sessionId)
        }
    }

    override fun processUrlAsFlow(
        url: String,
        cacheExpiryMs: Long?,
        sessionId: PeriodicIndexSessionId
    ): Flow<UrlProcessingEvent> {
        return processInternalAsFlow(url, cacheExpiryMs, sessionId) { html ->
            webpageLinkDiscoveryService.discoverAllLinks(html, url)
        }
    }

    private fun processInternalAsFlow(
        url: String,
        cacheExpiryMs: Long?,
        sessionId: SessionId,
        discoverLinks: suspend (html: String) -> List<WebpageLink>
    ): Flow<UrlProcessingEvent> = flow {
        logger.debug("Processing URL: {}", url)

        val normalizedUrl = normalizeUrlService.normalize(url) ?: url

        urlProcessingLockRegistry.withKeyLock(normalizedUrl) {
            when (val cacheResult = webpageCacheService.getCachedMarkdown(normalizedUrl, cacheExpiryMs)) {
                is CachedWebpageResult.Hit -> {
                    logger.debug("Cache hit for URL after lock acquisition: {}", normalizedUrl)
                    handleCachedResultAsFlow(url, cacheResult.webpageMarkdown, discoverLinks)
                        .collect { event -> emit(event) }
                    return@withKeyLock
                }
                is CachedWebpageResult.Miss, is CachedWebpageResult.Expired -> {
                    logger.debug("Cache miss/expired for URL, proceeding with processing: {}", normalizedUrl)
                }
            }

            try {
                when (val contentTypeResult = httpContentTypeResolutionService.resolve(normalizedUrl)) {
                    is ContentTypeResult.Html -> {
                        logger.debug("Detected HTML content for: {}", normalizedUrl)
                        processHtmlUrlAsFlow(normalizedUrl, sessionId, discoverLinks)
                            .collect { event -> emit(event) }
                    }
                    is ContentTypeResult.Pdf -> {
                        logger.debug("Detected PDF content for: {} ({} bytes)", normalizedUrl, contentTypeResult.bytes.size)
                        processPdfUrlAsFlow(normalizedUrl, contentTypeResult, sessionId)
                            .collect { event -> emit(event) }
                    }
                    is ContentTypeResult.Unsupported -> {
                        logger.debug("Unsupported content type for {}: {}", normalizedUrl, contentTypeResult.contentType)
                        // Throw exception for unsupported content (will be cached in catch block)
                        throw UnsupportedContentTypeException(normalizedUrl, contentTypeResult.contentType)
                    }
                }
            } catch (e: UrlProcessingException) {
                // Cache the failure before rethrowing
                cacheFailure(normalizedUrl, e, sessionId)
                throw e
            }
        }
    }

    private fun handleCachedResultAsFlow(
        originalUrl: String,
        cached: io.deepsearch.domain.models.entities.WebpageMarkdown,
        discoverLinks: suspend (html: String) -> List<WebpageLink>
    ): Flow<UrlProcessingEvent> = flow {
        if (cached.httpStatus != null && cached.httpStatus !in 200..299) {
            logger.debug("Cached failure for URL: {} (status: {})", originalUrl, cached.httpStatus)
            emit(UrlProcessingEvent.MarkdownExtractionComplete(originalUrl, "", null, null, wasCached = true))
            return@flow
        }

        val cachedHtml = cached.html
        val links = if (cachedHtml != null) discoverLinks(cachedHtml) else emptyList()

        // Emit links first, then markdown (consistent with non-cached flow)
        logger.debug("Emitting {} cached links for URL: {}", links.size, originalUrl)
        emit(UrlProcessingEvent.LinkDiscoveryComplete(originalUrl, links))
        
        logger.debug("Emitting cached markdown for URL: {} ({} chars)", originalUrl, cached.markdown?.length ?: 0)
        emit(UrlProcessingEvent.MarkdownExtractionComplete(originalUrl, cached.markdown ?: "", cached.title, cached.description, wasCached = true))
    }

    private fun processHtmlUrlAsFlow(
        normalizedUrl: String,
        sessionId: SessionId,
        discoverLinks: suspend (html: String) -> List<WebpageLink>
    ): Flow<UrlProcessingEvent> = channelFlow {
        browserRuntimePool.acquireRuntime { runtime ->
            val browser = runtime.createBrowser()
            
            try {
                val context = browser.createContext()
                val page = context.newPage()

                page.navigate(normalizedUrl)
                val extractedHtml = page.getFullHtml()

                // Create separate flows for each operation - these are cancellation-aware
                val linkDiscoveryFlow = flow {
                    val discoveredLinks = discoverLinks(extractedHtml)
                    logger.debug("Link discovery complete for {}: {} links", normalizedUrl, discoveredLinks.size)
                    emit(UrlProcessingEvent.LinkDiscoveryComplete(normalizedUrl, discoveredLinks))
                }

                val markdownExtractionFlow = flow {
                    try {
                        val extractionResult = webpageExtractionService.extractWebpage(page, sessionId)
                        logger.debug("Markdown extraction complete for {}: {} chars", normalizedUrl, extractionResult.markdown.length)

                        webpageCacheService.cacheWebpage(
                            url = normalizedUrl,
                            title = extractionResult.title,
                            description = extractionResult.description,
                            markdown = extractionResult.markdown,
                            html = extractedHtml,
                            httpStatus = 200,
                            httpReason = "OK",
                            mimeType = "text/html",
                            sessionId = sessionId
                        )

                        emit(UrlProcessingEvent.MarkdownExtractionComplete(
                            normalizedUrl, 
                            extractionResult.markdown,
                            extractionResult.title,
                            extractionResult.description,
                            wasCached = false
                        ))
                    } catch (e: Exception) {
                        // Wrap markdown extraction failures
                        throw MarkdownExtractionException(normalizedUrl, e)
                    }
                }

                // Merge both flows - they emit independently as each completes
                // When this flow is cancelled, merge stops collecting immediately and propagates cancellation
                merge(linkDiscoveryFlow, markdownExtractionFlow)
                    .onCompletion { cause ->
                        if (cause != null) {
                            logger.debug("Flow cancelled for {}: {}", normalizedUrl, cause.message)
                        }
                    }
                    .collect { event -> send(event) }
                    
            } finally {
                browser.close()
            }
        }
    }

    private fun processPdfUrlAsFlow(
        normalizedUrl: String,
        result: ContentTypeResult.Pdf,
        sessionId: SessionId
    ): Flow<UrlProcessingEvent> = flow {
        val markdown = pdfConversionService.convertPdfToMarkdown(result.bytes, sessionId)

        webpageCacheService.cacheWebpage(
            url = normalizedUrl,
            title = null,
            description = null,
            markdown = markdown,
            html = null,
            httpStatus = result.statusCode,
            httpReason = result.reasonPhrase,
            mimeType = result.mimeType,
            sessionId = sessionId
        )

        logger.debug("Processed PDF for URL: {} ({} chars)", normalizedUrl, markdown.length)

        // PDFs don't have discoverable links, only markdown
        emit(UrlProcessingEvent.MarkdownExtractionComplete(normalizedUrl, markdown, null, null, wasCached = false))
    }

    private suspend fun cacheFailure(
        normalizedUrl: String,
        exception: UrlProcessingException,
        sessionId: SessionId
    ) {
        val (statusCode, reasonPhrase, mimeType) = when (exception) {
            is HttpClientErrorException -> Triple(exception.statusCode, exception.reasonPhrase, null)
            is HttpServerErrorException -> Triple(exception.statusCode, exception.reasonPhrase, null)
            is UnsupportedContentTypeException -> Triple(200, "OK", exception.contentType)
            else -> Triple(0, exception.message ?: "Unknown error", null)
        }
        
        webpageCacheService.cacheWebpage(
            url = normalizedUrl,
            title = null,
            description = null,
            markdown = null,
            html = null,
            httpStatus = statusCode,
            httpReason = reasonPhrase,
            mimeType = mimeType,
            sessionId = sessionId
        )
    }
}


