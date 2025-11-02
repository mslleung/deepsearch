package io.deepsearch.application.services

import io.deepsearch.domain.browser.IBrowserRuntime
import io.deepsearch.domain.models.valueobjects.WebpageLink
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import io.deepsearch.application.services.IUrlContentProcessingService.*

interface IUrlContentProcessingService {
    /**
     * Events emitted during URL processing.
     * Links are discovered first (~5s), then markdown is extracted (~1min).
     */
    sealed interface UrlProcessingEvent {
        val url: String

        data class LinkDiscoveryComplete(
            override val url: String,
            val discoveredLinks: List<WebpageLink>
        ) : UrlProcessingEvent

        data class MarkdownExtractionComplete(
            override val url: String,
            val markdown: String
        ) : UrlProcessingEvent
    }

    /**
     * Process a URL and discover links relevant to the provided query.
     * Emits LinkDiscoveryComplete event first, then MarkdownExtractionComplete event.
     */
    fun processUrlAsFlow(
        url: String,
        query: String,
        runtime: IBrowserRuntime
    ): Flow<UrlProcessingEvent>

    /**
     * Process a URL and discover all links on the page (query-agnostic).
     * Emits LinkDiscoveryComplete event first, then MarkdownExtractionComplete event.
     */
    fun processUrlAsFlow(
        url: String,
        runtime: IBrowserRuntime
    ): Flow<UrlProcessingEvent>
}

class UrlContentProcessingService(
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
        runtime: IBrowserRuntime
    ): Flow<UrlProcessingEvent> {
        return processInternalAsFlow(url, runtime) { html ->
            webpageLinkDiscoveryService.discoverRelevantLinksByAgent(query, html)
        }
    }

    override fun processUrlAsFlow(
        url: String,
        runtime: IBrowserRuntime
    ): Flow<UrlProcessingEvent> {
        return processInternalAsFlow(url, runtime) { html ->
            webpageLinkDiscoveryService.discoverAllLinks(html, url)
        }
    }

    private fun processInternalAsFlow(
        url: String,
        runtime: IBrowserRuntime,
        discoverLinks: suspend (html: String) -> List<WebpageLink>
    ): Flow<UrlProcessingEvent> = flow {
        logger.debug("Processing URL: {}", url)

        val normalizedUrl = normalizeUrlService.normalize(url) ?: url

        urlProcessingLockRegistry.withKeyLock(normalizedUrl) {
            when (val cacheResult = webpageCacheService.getCachedMarkdown(normalizedUrl)) {
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

            when (val contentTypeResult = httpContentTypeResolutionService.resolve(normalizedUrl)) {
                is ContentTypeResult.Html -> {
                    logger.debug("Detected HTML content for: {}", normalizedUrl)
                    processHtmlUrlAsFlow(normalizedUrl, runtime, discoverLinks)
                        .collect { event -> emit(event) }
                }
                is ContentTypeResult.Pdf -> {
                    logger.debug("Detected PDF content for: {} ({} bytes)", normalizedUrl, contentTypeResult.bytes.size)
                    processPdfUrlAsFlow(normalizedUrl, contentTypeResult)
                        .collect { event -> emit(event) }
                }
                is ContentTypeResult.Unsupported -> {
                    logger.debug("Unsupported content type for {}: {}", normalizedUrl, contentTypeResult.contentType)
                    cacheUnsupportedContent(normalizedUrl, contentTypeResult)
                }
                is ContentTypeResult.Failed -> {
                    logger.debug("Failed to resolve content type for {}: {} {}", normalizedUrl, contentTypeResult.statusCode, contentTypeResult.reasonPhrase)
                    cacheFailedRequest(normalizedUrl, contentTypeResult)
                }
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
            emit(UrlProcessingEvent.MarkdownExtractionComplete(originalUrl, ""))
            return@flow
        }

        val cachedHtml = cached.html
        val links = if (cachedHtml != null) discoverLinks(cachedHtml) else emptyList()

        // Emit links first, then markdown (consistent with non-cached flow)
        logger.debug("Emitting {} cached links for URL: {}", links.size, originalUrl)
        emit(UrlProcessingEvent.LinkDiscoveryComplete(originalUrl, links))
        
        logger.debug("Emitting cached markdown for URL: {} ({} chars)", originalUrl, cached.markdown?.length ?: 0)
        emit(UrlProcessingEvent.MarkdownExtractionComplete(originalUrl, cached.markdown ?: ""))
    }

    private fun processHtmlUrlAsFlow(
        normalizedUrl: String,
        runtime: IBrowserRuntime,
        discoverLinks: suspend (html: String) -> List<WebpageLink>
    ): Flow<UrlProcessingEvent> = channelFlow {
        val browser = runtime.createBrowser()
        val context = browser.createContext()
        val page = context.newPage()
        
        try {
            page.navigate(normalizedUrl)
            val extractedHtml = page.getFullHtml()

            // Process link discovery and markdown extraction concurrently and independently
            coroutineScope {
                // Launch link discovery - emits as soon as ready
                val linkDiscoveryJob = launch {
                    val discoveredLinks = discoverLinks(extractedHtml)
                    logger.debug("Link discovery complete for {}: {} links", normalizedUrl, discoveredLinks.size)
                    send(UrlProcessingEvent.LinkDiscoveryComplete(normalizedUrl, discoveredLinks))
                }
                
                // Launch markdown extraction - emits as soon as ready
                val markdownExtractionJob = launch {
                    val extractedMarkdown = webpageExtractionService.extractWebpage(page)
                    logger.debug("Markdown extraction complete for {}: {} chars", normalizedUrl, extractedMarkdown.length)
                    
                    webpageCacheService.cacheWebpage(
                        url = normalizedUrl,
                        markdown = extractedMarkdown,
                        html = extractedHtml,
                        httpStatus = 200,
                        httpReason = "OK",
                        mimeType = "text/html"
                    )
                    
                    send(UrlProcessingEvent.MarkdownExtractionComplete(normalizedUrl, extractedMarkdown))
                }

                // Wait for both to complete before closing browser
                linkDiscoveryJob.join()
                markdownExtractionJob.join()
            }
        } finally {
            browser.close()
        }
    }

    private fun processPdfUrlAsFlow(
        normalizedUrl: String,
        result: ContentTypeResult.Pdf
    ): Flow<UrlProcessingEvent> = flow {
        val markdown = pdfConversionService.convertPdfToMarkdown(result.bytes)

        webpageCacheService.cacheWebpage(
            url = normalizedUrl,
            markdown = markdown,
            html = null,
            httpStatus = result.statusCode,
            httpReason = result.reasonPhrase,
            mimeType = result.mimeType
        )

        logger.debug("Processed PDF for URL: {} ({} chars)", normalizedUrl, markdown.length)

        // PDFs don't have discoverable links, only markdown
        emit(UrlProcessingEvent.MarkdownExtractionComplete(normalizedUrl, markdown))
    }

    private suspend fun cacheUnsupportedContent(
        normalizedUrl: String,
        result: ContentTypeResult.Unsupported
    ) {
        webpageCacheService.cacheWebpage(
            url = normalizedUrl,
            markdown = null,
            html = null,
            httpStatus = result.statusCode,
            httpReason = result.reasonPhrase,
            mimeType = result.contentType
        )
    }

    private suspend fun cacheFailedRequest(
        normalizedUrl: String,
        result: ContentTypeResult.Failed
    ) {
        webpageCacheService.cacheWebpage(
            url = normalizedUrl,
            markdown = null,
            html = null,
            httpStatus = result.statusCode,
            httpReason = result.reasonPhrase,
            mimeType = null
        )
    }
}


