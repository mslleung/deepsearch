package io.deepsearch.application.services

import io.deepsearch.domain.browser.IBrowser
import io.deepsearch.domain.models.valueobjects.WebpageLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory

interface IUrlContentProcessingService {
    /**
     * Result of processing a single URL: extracted markdown and discovered links.
     */
    data class UrlProcessingResult(
        val url: String,
        val markdown: String,
        val discoveredLinks: List<WebpageLink>
    )

    /**
     * Process a URL and discover links relevant to the provided query.
     */
    suspend fun processUrl(
        url: String,
        query: String,
        browser: IBrowser
    ): UrlProcessingResult

    /**
     * Process a URL and discover all links on the page (query-agnostic).
     */
    suspend fun processUrl(
        url: String,
        browser: IBrowser
    ): UrlProcessingResult
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

    override suspend fun processUrl(
        url: String,
        query: String,
        browser: IBrowser
    ): IUrlContentProcessingService.UrlProcessingResult {
        return processInternal(url, browser) { html ->
            webpageLinkDiscoveryService.discoverRelevantLinksByAgent(query, html)
        }
    }

    override suspend fun processUrl(
        url: String,
        browser: IBrowser
    ): IUrlContentProcessingService.UrlProcessingResult {
        return processInternal(url, browser) { html ->
            webpageLinkDiscoveryService.discoverAllLinks(html, url)
        }
    }

    private suspend fun processInternal(
        url: String,
        browser: IBrowser,
        discoverLinks: suspend (html: String) -> List<WebpageLink>
    ): IUrlContentProcessingService.UrlProcessingResult {
        logger.debug("Processing URL: {}", url)

        val normalizedUrl = normalizeUrlService.normalize(url) ?: url

        return urlProcessingLockRegistry.withKeyLock(normalizedUrl) {
            when (val cacheResult = webpageCacheService.getCachedMarkdown(normalizedUrl)) {
                is CachedWebpageResult.Hit -> {
                    logger.debug("Cache hit for URL after lock acquisition: {}", normalizedUrl)
                    return@withKeyLock handleCachedResult(url, cacheResult.webpageMarkdown, discoverLinks)
                }
                is CachedWebpageResult.Miss, is CachedWebpageResult.Expired -> {
                    logger.debug("Cache miss/expired for URL, proceeding with processing: {}", normalizedUrl)
                }
            }

            when (val contentTypeResult = httpContentTypeResolutionService.resolve(normalizedUrl)) {
                is ContentTypeResult.Html -> {
                    logger.debug("Detected HTML content for: {}", normalizedUrl)
                    processHtmlUrl(normalizedUrl, browser, discoverLinks)
                }
                is ContentTypeResult.Pdf -> {
                    logger.debug("Detected PDF content for: {} ({} bytes)", normalizedUrl, contentTypeResult.bytes.size)
                    processPdfUrl(normalizedUrl, contentTypeResult)
                }
                is ContentTypeResult.Unsupported -> {
                    logger.debug("Unsupported content type for {}: {}", normalizedUrl, contentTypeResult.contentType)
                    cacheUnsupportedContent(normalizedUrl, contentTypeResult)
                    IUrlContentProcessingService.UrlProcessingResult(normalizedUrl, "", emptyList())
                }
                is ContentTypeResult.Failed -> {
                    logger.debug("Failed to resolve content type for {}: {} {}", normalizedUrl, contentTypeResult.statusCode, contentTypeResult.reasonPhrase)
                    cacheFailedRequest(normalizedUrl, contentTypeResult)
                    IUrlContentProcessingService.UrlProcessingResult(normalizedUrl, "", emptyList())
                }
            }
        }
    }

    private suspend fun handleCachedResult(
        originalUrl: String,
        cached: io.deepsearch.domain.models.entities.WebpageMarkdown,
        discoverLinks: suspend (html: String) -> List<WebpageLink>
    ): IUrlContentProcessingService.UrlProcessingResult {
        if (cached.httpStatus != null && cached.httpStatus !in 200..299) {
            logger.debug("Cached failure for URL: {} (status: {})", originalUrl, cached.httpStatus)
            return IUrlContentProcessingService.UrlProcessingResult(originalUrl, "", emptyList())
        }

        val cachedHtml = cached.html
        val links = if (cachedHtml != null) discoverLinks(cachedHtml) else emptyList()

        return IUrlContentProcessingService.UrlProcessingResult(originalUrl, cached.markdown ?: "", links)
    }

    private suspend fun processHtmlUrl(
        normalizedUrl: String,
        browser: IBrowser,
        discoverLinks: suspend (html: String) -> List<WebpageLink>
    ): IUrlContentProcessingService.UrlProcessingResult = coroutineScope {
        val context = browser.createContext()
        val page = context.newPage()
        page.navigate(normalizedUrl)

        val extractedHtml = page.getFullHtml()

        val markdownDeferred = async {
            webpageExtractionService.extractWebpage(page)
        }
        val linksDeferred = async {
            discoverLinks(extractedHtml)
        }

        val extractedMarkdown = markdownDeferred.await()
        val discoveredLinks = linksDeferred.await()

        webpageCacheService.cacheWebpage(
            url = normalizedUrl,
            markdown = extractedMarkdown,
            html = extractedHtml,
            httpStatus = 200,
            httpReason = "OK",
            mimeType = "text/html"
        )

        logger.debug("Processed HTML for URL: {} ({} chars, {} links)", normalizedUrl, extractedMarkdown.length, discoveredLinks.size)

        IUrlContentProcessingService.UrlProcessingResult(normalizedUrl, extractedMarkdown, discoveredLinks)
    }

    private suspend fun processPdfUrl(
        normalizedUrl: String,
        result: ContentTypeResult.Pdf
    ): IUrlContentProcessingService.UrlProcessingResult {
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

        return IUrlContentProcessingService.UrlProcessingResult(normalizedUrl, markdown, emptyList())
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


