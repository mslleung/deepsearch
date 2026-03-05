package io.deepsearch.application.services

import io.deepsearch.domain.exceptions.*
import io.deepsearch.domain.models.valueobjects.OcrLanguage
import io.deepsearch.domain.models.valueobjects.PeriodicIndexSessionId
import io.deepsearch.domain.models.valueobjects.WebpageLink
import io.deepsearch.domain.proxy.ProxyConfiguration
import io.deepsearch.domain.services.INormalizeUrlService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import org.slf4j.LoggerFactory

interface IIndexingUrlProcessingService {
    /**
     * Process a URL for periodic indexing. Discovers all links on the page (query-agnostic)
     * and produces comprehensive markdown via the DOM extraction pipeline.
     *
     * Emits: [UrlProcessingEvent.LinkDiscoveryComplete], [UrlProcessingEvent.MarkdownExtractionComplete],
     * [UrlProcessingEvent.FileMarkdownExtractionComplete], [UrlProcessingEvent.PdfPreviewReady].
     */
    fun processUrlAsFlow(
        url: String,
        sessionId: PeriodicIndexSessionId,
        ocrLanguage: OcrLanguage = OcrLanguage.DEFAULT,
        proxyConfig: ProxyConfiguration = ProxyConfiguration.None
    ): Flow<UrlProcessingEvent>
}

class IndexingUrlProcessingService(
    private val normalizeUrlService: INormalizeUrlService,
    private val urlProcessingLockRegistry: UrlProcessingLockRegistry,
    private val httpContentTypeResolutionService: IHttpContentTypeResolutionService,
    private val webpageCacheService: IWebpageCacheService,
    private val webpageIndexingService: IWebpageIndexingService,
    private val browserPageResolver: IBrowserPageResolver,
    private val fileUrlProcessingService: IFileUrlProcessingService,
    private val webpageLinkDiscoveryService: IWebpageLinkDiscoveryService
) : IIndexingUrlProcessingService {

    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun processUrlAsFlow(
        url: String,
        sessionId: PeriodicIndexSessionId,
        ocrLanguage: OcrLanguage,
        proxyConfig: ProxyConfiguration
    ): Flow<UrlProcessingEvent> = flow {
        logger.debug("Processing URL for indexing: {}", url)

        val normalizedUrl = normalizeUrlService.normalize(url) ?: url

        urlProcessingLockRegistry.withKeyLock(normalizedUrl) {
            try {
                // Periodic indexing always forces refresh (maxCacheAge = 0)
                when (val cacheResult = webpageCacheService.getCachedMarkdown(normalizedUrl, 0)) {
                    is CachedWebpageResult.Hit -> {
                        logger.debug("Cache hit for URL after lock acquisition: {}", normalizedUrl)
                        handleCachedResult(url, cacheResult.webpageMarkdown)
                        return@withKeyLock
                    }
                    is CachedWebpageResult.Miss, is CachedWebpageResult.Expired, is CachedWebpageResult.Failure -> {
                        logger.debug("Cache miss/expired/failure for URL, proceeding with processing: {}", normalizedUrl)
                    }
                }

                when (val contentTypeResult = httpContentTypeResolutionService.resolve(normalizedUrl)) {
                    is ContentTypeResult.Html -> {
                        logger.debug("Detected HTML content for: {} ({} bytes)", normalizedUrl, contentTypeResult.bodyBytes.size)
                        processHtmlAsFlow(normalizedUrl, contentTypeResult.bodyBytes, sessionId, proxyConfig)
                            .collect { event -> emit(event) }
                    }
                    is ContentTypeResult.SupportedFile -> {
                        logger.debug(
                            "Detected supported file for: {} ({} bytes, type: {})",
                            normalizedUrl, contentTypeResult.bytes.size, contentTypeResult.mimeType
                        )
                        fileUrlProcessingService.processFileAsFlow(
                            normalizedUrl, contentTypeResult,
                            "Extract all relevant content", 0, sessionId
                        ) { markdown ->
                            discoverLinksForFile(markdown, contentTypeResult.bytes, contentTypeResult.mimeType, normalizedUrl)
                        }.collect { event -> emit(event) }
                    }
                    is ContentTypeResult.FileTooLarge -> {
                        throw FileTooLargeException(normalizedUrl, contentTypeResult.contentLength, contentTypeResult.maxSizeBytes)
                    }
                    is ContentTypeResult.Unsupported -> {
                        throw UnsupportedContentTypeException(normalizedUrl, contentTypeResult.contentType)
                    }
                }
            } catch (e: UrlProcessingException) {
                cacheFailure(normalizedUrl, e, sessionId)
                throw e
            }
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<UrlProcessingEvent>.handleCachedResult(
        originalUrl: String,
        cached: io.deepsearch.domain.models.entities.WebpageMarkdown
    ) {
        val linkRelevanceHtml = cached.cleanedLinkRelevanceHtml
        val links = if (linkRelevanceHtml != null) {
            webpageLinkDiscoveryService.discoverAllLinks(linkRelevanceHtml, originalUrl)
        } else {
            emptyList()
        }

        emit(UrlProcessingEvent.LinkDiscoveryComplete(originalUrl, links))
        emit(
            UrlProcessingEvent.MarkdownExtractionComplete(
                originalUrl,
                cached.markdown ?: "",
                cached.title,
                cached.description,
                wasCached = true,
                imageMapping = cached.imageMapping
            )
        )
    }

    private fun processHtmlAsFlow(
        normalizedUrl: String,
        cachedHtmlBody: ByteArray,
        sessionId: PeriodicIndexSessionId,
        proxyConfig: ProxyConfiguration
    ): Flow<UrlProcessingEvent> = channelFlow {
        browserPageResolver.withPageForCachedHtml(normalizedUrl, cachedHtmlBody, proxyConfig) { page ->
            val extractedHtml = page.getFullHtml()

            val linkDiscoveryFlow = flow {
                val discoveredLinks = webpageLinkDiscoveryService.discoverAllLinks(extractedHtml, normalizedUrl)
                emit(UrlProcessingEvent.LinkDiscoveryComplete(normalizedUrl, discoveredLinks))
            }

            val contentFlow = flow {
                try {
                    page.waitForLoad()

                    val indexResult = webpageIndexingService.indexWebpage(page, sessionId)
                    logger.debug("Indexing complete for {}: {} chars markdown", normalizedUrl, indexResult.markdown.length)

                    webpageCacheService.cacheWebpage(
                        url = normalizedUrl,
                        title = indexResult.title,
                        description = indexResult.description,
                        markdown = indexResult.markdown,
                        html = extractedHtml,
                        httpStatus = 200,
                        httpReason = "OK",
                        mimeType = "text/html",
                        sessionId = sessionId
                    )

                    emit(
                        UrlProcessingEvent.MarkdownExtractionComplete(
                            normalizedUrl,
                            indexResult.markdown,
                            indexResult.title,
                            indexResult.description,
                            wasCached = false
                        )
                    )
                } catch (e: Exception) {
                    throw MarkdownExtractionException(normalizedUrl, e)
                }
            }

            merge(linkDiscoveryFlow, contentFlow)
                .onCompletion { cause ->
                    if (cause != null) logger.debug("Flow cancelled for {}: {}", normalizedUrl, cause.message)
                }
                .collect { event -> send(event) }
        }
    }

    private fun discoverLinksForFile(
        markdown: String,
        fileBytes: ByteArray,
        mimeType: String,
        sourceUrl: String
    ): List<WebpageLink> {
        return if (mimeType.contains("pdf", ignoreCase = true)) {
            webpageLinkDiscoveryService.discoverAllLinksFromPdf(fileBytes, sourceUrl)
        } else {
            webpageLinkDiscoveryService.discoverLinksFromText(markdown, sourceUrl)
        }
    }

    private suspend fun cacheFailure(
        normalizedUrl: String,
        exception: UrlProcessingException,
        sessionId: PeriodicIndexSessionId
    ) {
        val (statusCode, reasonPhrase, mimeType) = when (exception) {
            is HttpClientErrorException -> Triple(exception.statusCode, exception.reasonPhrase, null)
            is HttpServerErrorException -> Triple(exception.statusCode, exception.reasonPhrase, null)
            is UnsupportedContentTypeException -> Triple(200, "OK", exception.contentType)
            else -> Triple(0, exception.message ?: "Unknown error", null)
        }

        webpageCacheService.cacheWebpage(
            url = normalizedUrl,
            title = null, description = null, markdown = null, html = null,
            httpStatus = statusCode, httpReason = reasonPhrase, mimeType = mimeType,
            sessionId = sessionId
        )
    }
}
