package io.deepsearch.application.services

import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.models.valueobjects.WebpageLink
import io.deepsearch.domain.exceptions.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import io.deepsearch.application.services.IUrlContentProcessingService.*
import io.deepsearch.domain.models.valueobjects.OcrLanguage
import io.deepsearch.domain.models.valueobjects.PeriodicIndexSessionId
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.services.INormalizeUrlService
import io.deepsearch.domain.repositories.IWebpageImageLinkageRepository
import io.deepsearch.domain.proxy.ProxyConfiguration

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

        /**
         * Emitted immediately after HTML is loaded, before LLM processing.
         * Contains cleaned HTML for preview shortlist evaluation.
         * Only emitted for uncached HTML URLs.
         */
        data class HtmlPreviewReady(
            override val url: String,
            val cleanedHtml: String,
            val title: String?,
            val description: String?
        ) : UrlProcessingEvent
    }

    /**
     * Process a URL and discover links relevant to the provided query.
     * Emits LinkDiscoveryComplete event first, then MarkdownExtractionComplete event.
     * 
     * @param proxyConfig User's proxy configuration choice:
     *                    - None: direct connection
     *                    - Custom: user's custom proxy
     *                    - Premium: Proxyrack residential proxy
     *                    - FreeRotating: fanout to multiple free proxies
     */
    fun processUrlAsFlow(
        url: String,
        query: String,
        maxCacheAge: Long? = null,
        sessionId: QuerySessionId,
        ocrLanguage: OcrLanguage = OcrLanguage.DEFAULT,
        proxyConfig: ProxyConfiguration = ProxyConfiguration.None
    ): Flow<UrlProcessingEvent>

    /**
     * Process a URL and discover all links on the page (query-agnostic).
     * Used for periodic index jobs.
     * Emits LinkDiscoveryComplete event first, then MarkdownExtractionComplete event.
     * 
     * @param proxyConfig User's proxy configuration choice.
     */
    fun processUrlAsFlow(
        url: String,
        sessionId: PeriodicIndexSessionId,
        ocrLanguage: OcrLanguage = OcrLanguage.DEFAULT,
        proxyConfig: ProxyConfiguration = ProxyConfiguration.None
    ): Flow<UrlProcessingEvent>
}

class UrlContentProcessingService(
    private val browserPool: IBrowserPool,
    private val proxyResolutionService: IProxyResolutionService,
    private val normalizeUrlService: INormalizeUrlService,
    private val webpageCacheService: IWebpageCacheService,
    private val httpContentTypeResolutionService: IHttpContentTypeResolutionService,
    private val webpageExtractionService: IWebpageExtractionService,
    private val webpageLinkDiscoveryService: IWebpageLinkDiscoveryService,
    private val fileSearchService: IFileSearchService,
    private val tokenUsageService: ILlmTokenUsageService,
    private val urlProcessingLockRegistry: UrlProcessingLockRegistry,
    private val webpageImageLinkageRepository: IWebpageImageLinkageRepository,
    private val htmlPreviewService: IHtmlPreviewService
) : IUrlContentProcessingService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override fun processUrlAsFlow(
        url: String,
        query: String,
        maxCacheAge: Long?,
        sessionId: QuerySessionId,
        ocrLanguage: OcrLanguage,
        proxyConfig: ProxyConfiguration
    ): Flow<UrlProcessingEvent> {
        return processInternalAsFlow(
            url = url,
            query = query,
            maxCacheAge = maxCacheAge,
            sessionId = sessionId,
            ocrLanguage = ocrLanguage,
            proxyConfig = proxyConfig,
            discoverLinks = { html ->
                webpageLinkDiscoveryService.discoverRelevantLinksByAgent(query, html, url, sessionId)
            },
            discoverLinksForFile = { markdown, fileBytes, mimeType, sourceUrl ->
                // Use LLM to find query-relevant links from file search results
                webpageLinkDiscoveryService.discoverRelevantLinksFromText(query, markdown, sourceUrl, sessionId)
            }
        )
    }

    override fun processUrlAsFlow(
        url: String,
        sessionId: PeriodicIndexSessionId,
        ocrLanguage: OcrLanguage,
        proxyConfig: ProxyConfiguration
    ): Flow<UrlProcessingEvent> {
        // max cache age is set to 0 so the cache will always expire, this is because periodic index should forcefully refresh everything
        // For periodic index, we use a generic query for file search
        return processInternalAsFlow(
            url = url,
            query = "Extract all relevant content",
            maxCacheAge = 0,
            sessionId = sessionId,
            ocrLanguage = ocrLanguage,
            proxyConfig = proxyConfig,
            discoverLinks = { html ->
                webpageLinkDiscoveryService.discoverAllLinks(html, url)
            },
            discoverLinksForFile = { markdown, fileBytes, mimeType, sourceUrl ->
                // Extract all links without LLM - use PDF parser for PDFs, regex for others
                if (mimeType.contains("pdf", ignoreCase = true)) {
                    webpageLinkDiscoveryService.discoverAllLinksFromPdf(fileBytes, sourceUrl)
                } else {
                    webpageLinkDiscoveryService.discoverLinksFromText(markdown, sourceUrl)
                }
            }
        )
    }

    private fun processInternalAsFlow(
        url: String,
        query: String,
        maxCacheAge: Long?,
        sessionId: SessionId,
        ocrLanguage: OcrLanguage,
        proxyConfig: ProxyConfiguration,
        discoverLinks: suspend (html: String) -> List<WebpageLink>,
        discoverLinksForFile: suspend (markdown: String, fileBytes: ByteArray, mimeType: String, sourceUrl: String) -> List<WebpageLink>
    ): Flow<UrlProcessingEvent> = flow {
        logger.debug("Processing URL: {}", url)

        val normalizedUrl = normalizeUrlService.normalize(url) ?: url

        urlProcessingLockRegistry.withKeyLock(normalizedUrl) {
            try {
                when (val cacheResult = webpageCacheService.getCachedMarkdown(normalizedUrl, maxCacheAge)) {
                    is CachedWebpageResult.Hit -> {
                        logger.debug("Cache hit for URL after lock acquisition: {}", normalizedUrl)
                        handleCachedResultAsFlow(url, cacheResult.webpageMarkdown, discoverLinks)
                            .collect { event -> emit(event) }
                        return@withKeyLock
                    }

                    is CachedWebpageResult.Miss, is CachedWebpageResult.Expired, is CachedWebpageResult.Failure -> {
                        // non-html links will always miss, failures are retried
                        logger.debug("Cache miss/expired/failure for URL, proceeding with processing: {}", normalizedUrl)
                    }
                }

                when (val contentTypeResult = httpContentTypeResolutionService.resolve(normalizedUrl)) {
                    is ContentTypeResult.Html -> {
                        logger.debug("Detected HTML content for: {}", normalizedUrl)
                        processHtmlUrlAsFlow(normalizedUrl, sessionId, ocrLanguage, proxyConfig, discoverLinks)
                            .collect { event -> emit(event) }
                    }

                    is ContentTypeResult.SupportedFile -> {
                        logger.debug(
                            "Detected supported file for: {} ({} bytes, type: {})",
                            normalizedUrl, contentTypeResult.bytes.size, contentTypeResult.mimeType
                        )

                        processFileUrlAsFlow(
                            normalizedUrl,
                            contentTypeResult,
                            query,
                            maxCacheAge,
                            sessionId
                        ) { markdown ->
                            discoverLinksForFile(
                                markdown,
                                contentTypeResult.bytes,
                                contentTypeResult.mimeType,
                                normalizedUrl
                            )
                        }.collect { event -> emit(event) }
                    }

                    is ContentTypeResult.FileTooLarge -> {
                        logger.warn(
                            "File too large for {}: {} bytes (max: {} bytes)",
                            normalizedUrl, contentTypeResult.contentLength, contentTypeResult.maxSizeBytes
                        )
                        throw FileTooLargeException(
                            normalizedUrl,
                            contentTypeResult.contentLength,
                            contentTypeResult.maxSizeBytes
                        )
                    }

                    is ContentTypeResult.Unsupported -> {
                        logger.debug(
                            "Unsupported content type for {}: {}",
                            normalizedUrl,
                            contentTypeResult.contentType
                        )
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
        val cachedHtml = cached.html
        val links = if (cachedHtml != null) discoverLinks(cachedHtml) else emptyList()

        // Emit links first, then markdown (consistent with non-cached flow)
        logger.debug("Emitting {} cached links for URL: {}", links.size, originalUrl)
        emit(UrlProcessingEvent.LinkDiscoveryComplete(originalUrl, links))

        logger.debug("Emitting cached markdown for URL: {} ({} chars)", originalUrl, cached.markdown?.length ?: 0)
        emit(
            UrlProcessingEvent.MarkdownExtractionComplete(
                originalUrl,
                cached.markdown ?: "",
                cached.title,
                cached.description,
                wasCached = true
            )
        )
    }

    private fun processHtmlUrlAsFlow(
        normalizedUrl: String,
        sessionId: SessionId,
        ocrLanguage: OcrLanguage,
        proxyConfig: ProxyConfiguration,
        discoverLinks: suspend (html: String) -> List<WebpageLink>
    ): Flow<UrlProcessingEvent> = channelFlow {
        // Resolve proxy config and execute with appropriate browser pool method (navigation is handled internally)
        val navigateStart = System.currentTimeMillis()
        withResolvedPage(normalizedUrl, proxyConfig) { page ->
            val navigateTime = System.currentTimeMillis() - navigateStart
            
            val getHtmlStart = System.currentTimeMillis()
            val extractedHtml = page.getFullHtml()
            val getHtmlTime = System.currentTimeMillis() - getHtmlStart
            
            logger.debug(
                "Browser timing for {}: navigate={}ms, getFullHtml={}ms ({} chars)",
                normalizedUrl, navigateTime, getHtmlTime, extractedHtml.length
            )

            // Emit HTML preview immediately (no LLM) for early evaluation by preview path
            // This allows the preview shortlist agent to start processing while full markdown is being extracted
            val htmlPreviewFlow = flow {
                val result = htmlPreviewService.prepareHtmlPreview(extractedHtml, normalizedUrl)
                logger.debug("HTML preview ready for {}: {} chars", normalizedUrl, result.cleanedHtml.length)
                
                // Emit FIRST for low latency - preview path shouldn't wait for DB operations
                emit(
                    UrlProcessingEvent.HtmlPreviewReady(
                        normalizedUrl,
                        result.cleanedHtml,
                        result.title,
                        result.description
                    )
                )
                
                // Cache the preview content so sources are available if search completes before full extraction
                // When full markdown is extracted, it will replace this via upsert (isPreview=false overwrites isPreview=true)
                // For preview, we store the cleaned HTML as markdown for embedding generation
                // Note: html=null to avoid slow 1MB insert - full extraction will store it
                webpageCacheService.cacheWebpage(
                    url = normalizedUrl,
                    title = result.title,
                    description = result.description,
                    markdown = result.cleanedHtml,  // Use cleaned HTML for preview embedding
                    html = null,  // Skip raw HTML for preview - full extraction will store it
                    httpStatus = 200,
                    httpReason = "OK",
                    mimeType = "text/html",
                    sessionId = sessionId,
                    isPreview = true
                )
            }

            // Create separate flows for each operation - these are cancellation-aware
            val linkDiscoveryFlow = flow {
                val discoveredLinks = discoverLinks(extractedHtml)
                logger.debug("Link discovery complete for {}: {} links", normalizedUrl, discoveredLinks.size)
                emit(UrlProcessingEvent.LinkDiscoveryComplete(normalizedUrl, discoveredLinks))
            }

            val markdownExtractionFlow = flow {
                try {
                    // Wait for full page load before extraction (navigate only waits for DOMContentLoaded)
                    // This ensures all resources are loaded for accurate markdown extraction
                    page.waitForLoad()
                    
                    // Extract webpage content - this internally stores images in their own
                    // committed transactions via webpageImageRepository.batchUpsert()
                    val extractionResult = webpageExtractionService.extractWebpage(page, sessionId, ocrLanguage)
                    logger.debug(
                        "Markdown extraction complete for {}: {} chars",
                        normalizedUrl,
                        extractionResult.markdown.length
                    )

                    // Cache the webpage content (has its own transaction)
                    // isPreview = false to indicate this is full LLM-processed markdown
                    webpageCacheService.cacheWebpage(
                        url = normalizedUrl,
                        title = extractionResult.title,
                        description = extractionResult.description,
                        markdown = extractionResult.markdown,
                        html = extractedHtml,
                        httpStatus = 200,
                        httpReason = "OK",
                        mimeType = "text/html",
                        sessionId = sessionId,
                        isPreview = false
                    )

                    // Update URL-to-image linkages in a separate transaction
                    // Images are already committed by extractWebpage(), so FK constraints are satisfied
                    if (extractionResult.imageHashes.isNotEmpty()) {
                        webpageImageLinkageRepository.upsertLinkages(
                            normalizedUrl,
                            extractionResult.imageHashes
                        )
                    }

                    emit(
                        UrlProcessingEvent.MarkdownExtractionComplete(
                            normalizedUrl,
                            extractionResult.markdown,
                            extractionResult.title,
                            extractionResult.description,
                            wasCached = false
                        )
                    )
                } catch (e: Exception) {
                    // Wrap markdown extraction failures
                    throw MarkdownExtractionException(normalizedUrl, e)
                }
            }

            // Merge all flows - HTML preview emits first (synchronous), then LLM flows run in parallel
            // When this flow is cancelled, merge stops collecting immediately and propagates cancellation
            merge(htmlPreviewFlow, linkDiscoveryFlow, markdownExtractionFlow)
                .onCompletion { cause ->
                    if (cause != null) {
                        logger.debug("Flow cancelled for {}: {}", normalizedUrl, cause.message)
                    }
                }
                .collect { event -> send(event) }
        }
    }

    /**
     * Execute block with resolved proxy configuration.
     * Handles navigation internally and orchestrates fanout if multiple proxies are returned.
     */
    private suspend fun <T> withResolvedPage(
        url: String,
        proxyConfig: ProxyConfiguration,
        block: suspend (IBrowserPage) -> T
    ): T {
        val proxyUrls = proxyResolutionService.resolve(url, proxyConfig)
        return if (proxyUrls.size <= 1) {
            browserPool.withPage(proxyUrls.firstOrNull()) { page ->
                page.navigate(url)
                block(page)
            }
        } else {
            withProxyFanout(proxyUrls, url, block)
        }
    }

    /**
     * Try multiple proxies in parallel. First successful navigation wins and proceeds with extraction.
     * Other attempts are cancelled once a winner is selected.
     */
    private suspend fun <T> withProxyFanout(
        proxyUrls: List<String>,
        navigateUrl: String,
        block: suspend (IBrowserPage) -> T
    ): T = coroutineScope {
        logger.debug("Fanout to {} proxies for {}", proxyUrls.size, navigateUrl)

        val result = CompletableDeferred<T>()
        val winnerSelected = AtomicBoolean(false)
        val failureCount = AtomicInteger(0)

        val jobs = proxyUrls.map { proxyUrl ->
            launch {
                try {
                    browserPool.withPage(proxyUrl) { page ->
                        page.navigate(navigateUrl)

                        // First successful navigation wins - others just exit
                        if (winnerSelected.compareAndSet(false, true)) {
                            val output = block(page)
                            result.complete(output)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.debug("Proxy {} failed: {}", proxyUrl, e.message)
                    if (failureCount.incrementAndGet() == proxyUrls.size) {
                        result.completeExceptionally(
                            AllProxiesFailedException(proxyUrls.first(), proxyUrls.size, e)
                        )
                    }
                }
            }
        }

        try {
            result.await()
        } finally {
            jobs.forEach { it.cancel() }
        }
    }

    /**
     * Process a supported file (PDF, docx, etc.) using Gemini File Search.
     *
     * Flow (similar to processHtmlUrlAsFlow):
     * 1. Use FileSearchService to ingest (upload if needed) the file
     * 2. Query the file search store for relevant content
     * 3. Emit MarkdownExtractionComplete with file search results
     * 4. Discover links from the extracted markdown (not the entire file)
     * 5. Emit LinkDiscoveryComplete with discovered links
     *
     * @param discoverLinks Lambda to discover links from the extracted markdown.
     *        For relevant links: uses LLM to find query-relevant URLs.
     *        For all links: extracts all URLs using regex/parser.
     */
    private fun processFileUrlAsFlow(
        normalizedUrl: String,
        result: ContentTypeResult.SupportedFile,
        query: String,
        maxCacheAge: Long?,
        sessionId: SessionId,
        discoverLinks: suspend (markdown: String) -> List<WebpageLink>
    ): Flow<UrlProcessingEvent> = channelFlow {
        logger.debug(
            "Processing file: {} ({} bytes, type: {})",
            normalizedUrl, result.bytes.size, result.mimeType
        )

        // First, ingest the file (upload if needed)
        val ingestResult = fileSearchService.ingest(
            url = normalizedUrl,
            fileBytes = result.bytes,
            mimeType = result.mimeType,
            maxCacheAge = maxCacheAge
        )

        logger.debug(
            "File ingestion complete for {}: {} (uploaded: {})",
            normalizedUrl, ingestResult.fileInfo.displayName, ingestResult.wasUploaded
        )

        // Then query the file search store for relevant content
        val queryResult = fileSearchService.query(
            domain = ingestResult.storeInfo.domain,
            query = query,
            sessionId = sessionId
        )

        logger.debug(
            "File query complete for {}: {} chars markdown",
            normalizedUrl, queryResult.markdown.length
        )

        send(
            UrlProcessingEvent.MarkdownExtractionComplete(
                url = normalizedUrl,
                markdown = queryResult.markdown,
                title = ingestResult.fileInfo.displayName,
                description = "File from ${ingestResult.fileInfo.sourceUrl}",
                wasCached = !ingestResult.wasUploaded
            )
        )

        val discoveredLinks = discoverLinks(queryResult.markdown)
        send(UrlProcessingEvent.LinkDiscoveryComplete(normalizedUrl, discoveredLinks))
        logger.debug("Link discovery complete for {}: {} links", normalizedUrl, discoveredLinks.size)
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
            sessionId = sessionId,
            isPreview = false
        )
    }
}
