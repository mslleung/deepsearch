package io.deepsearch.application.services

import io.deepsearch.domain.browser.IBrowserPool
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
import io.deepsearch.domain.models.valueobjects.OcrLanguage
import io.deepsearch.domain.models.valueobjects.PeriodicIndexSessionId
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.services.INormalizeUrlService
import io.deepsearch.domain.repositories.IWebpageImageLinkageRepository

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
         * Contains simple programmatic text extraction (no LLM) for early evaluation.
         * Only emitted for uncached HTML URLs.
         */
        data class SimpleTextExtractionComplete(
            override val url: String,
            val text: String,
            val title: String?,
            val description: String?
        ) : UrlProcessingEvent
    }

    /**
     * Process a URL and discover links relevant to the provided query.
     * Emits LinkDiscoveryComplete event first, then MarkdownExtractionComplete event.
     */
    fun processUrlAsFlow(
        url: String,
        query: String,
        maxCacheAge: Long? = null,
        sessionId: QuerySessionId,
        ocrLanguage: OcrLanguage = OcrLanguage.DEFAULT
    ): Flow<UrlProcessingEvent>

    /**
     * Process a URL and discover all links on the page (query-agnostic).
     * Used for periodic index jobs.
     * Emits LinkDiscoveryComplete event first, then MarkdownExtractionComplete event.
     */
    fun processUrlAsFlow(
        url: String,
        sessionId: PeriodicIndexSessionId,
        ocrLanguage: OcrLanguage = OcrLanguage.DEFAULT
    ): Flow<UrlProcessingEvent>
}

class UrlContentProcessingService(
    private val browserPool: IBrowserPool,
    private val normalizeUrlService: INormalizeUrlService,
    private val webpageCacheService: IWebpageCacheService,
    private val httpContentTypeResolutionService: IHttpContentTypeResolutionService,
    private val webpageExtractionService: IWebpageExtractionService,
    private val webpageLinkDiscoveryService: IWebpageLinkDiscoveryService,
    private val fileSearchService: IFileSearchService,
    private val tokenUsageService: ILlmTokenUsageService,
    private val urlProcessingLockRegistry: UrlProcessingLockRegistry,
    private val webpageImageLinkageRepository: IWebpageImageLinkageRepository,
    private val simpleTextExtractionService: ISimpleTextExtractionService
) : IUrlContentProcessingService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override fun processUrlAsFlow(
        url: String,
        query: String,
        maxCacheAge: Long?,
        sessionId: QuerySessionId,
        ocrLanguage: OcrLanguage
    ): Flow<UrlProcessingEvent> {
        return processInternalAsFlow(
            url = url,
            query = query,
            maxCacheAge = maxCacheAge,
            sessionId = sessionId,
            ocrLanguage = ocrLanguage,
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
        ocrLanguage: OcrLanguage
    ): Flow<UrlProcessingEvent> {
        // max cache age is set to 0 so the cache will always expire, this is because periodic index should forcefully refresh everything
        // For periodic index, we use a generic query for file search
        return processInternalAsFlow(
            url = url,
            query = "Extract all relevant content",
            maxCacheAge = 0,
            sessionId = sessionId,
            ocrLanguage = ocrLanguage,
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

                    is CachedWebpageResult.Miss, is CachedWebpageResult.Expired -> {
                        // non-html links will always miss
                        logger.debug("Cache miss/expired for URL, proceeding with processing: {}", normalizedUrl)
                    }
                }

                when (val contentTypeResult = httpContentTypeResolutionService.resolve(normalizedUrl)) {
                    is ContentTypeResult.Html -> {
                        logger.debug("Detected HTML content for: {}", normalizedUrl)
                        processHtmlUrlAsFlow(normalizedUrl, sessionId, ocrLanguage, discoverLinks)
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
        discoverLinks: suspend (html: String) -> List<WebpageLink>
    ): Flow<UrlProcessingEvent> = channelFlow {
        browserPool.withPage { page ->
            page.navigate(normalizedUrl)
            val extractedHtml = page.getFullHtml()

            // Emit simple text extraction immediately (no LLM) for early evaluation
            // This allows the shortlist agent to start processing while full markdown is being extracted
            val simpleTextFlow = flow {
                val result = simpleTextExtractionService.extractSimpleText(extractedHtml, normalizedUrl)
                logger.debug("Simple text extraction complete for {}: {} chars", normalizedUrl, result.text.length)
                emit(
                    UrlProcessingEvent.SimpleTextExtractionComplete(
                        normalizedUrl,
                        result.text,
                        result.title,
                        result.description
                    )
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
                    // Extract webpage content - this internally stores images in their own
                    // committed transactions via webpageImageRepository.batchUpsert()
                    val extractionResult = webpageExtractionService.extractWebpage(page, sessionId, ocrLanguage)
                    logger.debug(
                        "Markdown extraction complete for {}: {} chars",
                        normalizedUrl,
                        extractionResult.markdown.length
                    )

                    // Cache the webpage content (has its own transaction)
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

            // Merge all flows - simple text emits first (synchronous), then LLM flows run in parallel
            // When this flow is cancelled, merge stops collecting immediately and propagates cancellation
            merge(simpleTextFlow, linkDiscoveryFlow, markdownExtractionFlow)
                .onCompletion { cause ->
                    if (cause != null) {
                        logger.debug("Flow cancelled for {}: {}", normalizedUrl, cause.message)
                    }
                }
                .collect { event -> send(event) }
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
            sessionId = sessionId
        )
    }
}


