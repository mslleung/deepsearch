package io.deepsearch.application.services

import io.deepsearch.domain.browser.IBrowser
import io.deepsearch.domain.models.valueobjects.WebpageLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Result of processing a single URL: extracted markdown and discovered links.
 */
data class UrlProcessingResult(
    val url: String,
    val markdown: String,
    val discoveredLinks: List<WebpageLink>
)

interface IUrlContentProcessingService {
    /**
     * Process a URL: extract markdown and discover links.
     * Routes based on content type (HTML, PDF, etc.) and uses cache to avoid repeated navigation.
     * @param url The URL to process (original, not normalized)
     * @param query The search query for link discovery
     * @param browser Browser instance to use for navigation
     * @return UrlProcessingResult with markdown and discovered links
     */
    suspend fun processUrl(
        url: String,
        query: String,
        browser: IBrowser
    ): UrlProcessingResult
}

class UrlContentProcessingService(
    private val normalizeUrlService: INormalizeUrlService,
    private val webpageCacheService: IWebpageCacheService,
    private val httpContentTypeResolutionService: IHttpContentTypeResolutionService,
    private val webpageExtractionService: IWebpageExtractionService,
    private val webpageLinkDiscoveryService: IWebpageLinkDiscoveryService,
    private val pdfConversionService: IPdfConversionService
) : IUrlContentProcessingService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    
    /**
     * Per-URL mutexes to prevent concurrent processing of the same URL.
     * Ensures only one request processes a given URL at a time, while others wait
     * and then benefit from the cache populated by the first request.
     */
    private val urlMutexes = ConcurrentHashMap<String, Mutex>()

    override suspend fun processUrl(
        url: String,
        query: String,
        browser: IBrowser
    ): UrlProcessingResult {
        logger.debug("Processing URL: {}", url)

        // Normalize URL for deduplication and cache lookup
        val normalizedUrl = normalizeUrlService.normalize(url) ?: url
        
        // Get or create mutex for this URL to prevent concurrent processing
        val mutex = urlMutexes.computeIfAbsent(normalizedUrl) { Mutex() }
        
        return mutex.withLock {
            try {
                // Double-check cache after acquiring lock (another request may have completed)
                when (val cacheResult = webpageCacheService.getCachedMarkdown(normalizedUrl)) {
                    is CachedWebpageResult.Hit -> {
                        logger.debug("Cache hit for URL after lock acquisition: {}", normalizedUrl)
                        return@withLock handleCachedResult(url, query, cacheResult.webpageMarkdown)
                    }
                    is CachedWebpageResult.Miss, is CachedWebpageResult.Expired -> {
                        // Cache miss or expired - proceed with processing
                        logger.debug("Cache miss/expired for URL, proceeding with processing: {}", normalizedUrl)
                    }
                }

                // Resolve content type and process accordingly
                val result = when (val contentTypeResult = httpContentTypeResolutionService.resolve(normalizedUrl)) {
                    is ContentTypeResult.Html -> {
                        logger.debug("Detected HTML content for: {}", normalizedUrl)
                        processHtmlUrl(normalizedUrl, query, browser)
                    }
                    is ContentTypeResult.Pdf -> {
                        logger.debug("Detected PDF content for: {} ({} bytes)", normalizedUrl, contentTypeResult.bytes.size)
                        processPdfUrl(normalizedUrl, contentTypeResult)
                    }
                    is ContentTypeResult.Unsupported -> {
                        logger.debug("Unsupported content type for {}: {}", normalizedUrl, contentTypeResult.contentType)
                        cacheUnsupportedContent(normalizedUrl, contentTypeResult)
                        UrlProcessingResult(normalizedUrl, "", emptyList())
                    }
                    is ContentTypeResult.Failed -> {
                        logger.debug("Failed to resolve content type for {}: {} {}", normalizedUrl, contentTypeResult.statusCode, contentTypeResult.reasonPhrase)
                        cacheFailedRequest(normalizedUrl, contentTypeResult)
                        UrlProcessingResult(normalizedUrl, "", emptyList())
                    }
                }
                
                result
            } finally {
                // Clean up mutex if no one is waiting (best-effort to prevent memory leak)
                // Note: This is safe because ConcurrentHashMap.remove with predicate is atomic
                if (!mutex.isLocked) {
                    urlMutexes.remove(normalizedUrl, mutex)
                }
            }
        }
    }
    
    /**
     * Handle cached result: extract markdown and perform link discovery if needed.
     */
    private suspend fun handleCachedResult(
        originalUrl: String,
        query: String,
        cached: io.deepsearch.domain.models.entities.WebpageMarkdown
    ): UrlProcessingResult {
        // Check if it's a cached failure
        if (cached.httpStatus != null && cached.httpStatus !in 200..299) {
            logger.debug("Cached failure for URL: {} (status: {})", originalUrl, cached.httpStatus)
            return UrlProcessingResult(originalUrl, "", emptyList())
        }

        // For cached HTML, perform link discovery
        val cachedHtml = cached.html
        val links = if (cachedHtml != null) {
            webpageLinkDiscoveryService.discoverRelevantLinksByAgent(query, cachedHtml)
        } else {
            emptyList()
        }

        return UrlProcessingResult(originalUrl, cached.markdown ?: "", links)
    }

    private suspend fun processHtmlUrl(
        normalizedUrl: String,
        query: String,
        browser: IBrowser
    ): UrlProcessingResult = coroutineScope {
        // Navigate and extract
        val context = browser.createContext()
        val page = context.newPage()
        page.navigate(normalizedUrl)

        // webpageExtractionService.extractWebpage modifies the webpage, so we get the html first
        val extractedHtml = page.getFullHtml()

        // Extract markdown and discover links in parallel
        val markdownDeferred = async {
            webpageExtractionService.extractWebpage(page)
        }
        val linksDeferred = async {
            webpageLinkDiscoveryService.discoverRelevantLinksByAgent(query, extractedHtml)
        }

        val extractedMarkdown = markdownDeferred.await()
        val discoveredLinks = linksDeferred.await()

        // Cache the result
        webpageCacheService.cacheWebpage(
            url = normalizedUrl,
            markdown = extractedMarkdown,
            html = extractedHtml,
            httpStatus = 200,
            httpReason = "OK",
            mimeType = "text/html"
        )

        logger.debug("Processed HTML for URL: {} ({} chars, {} links)", normalizedUrl, extractedMarkdown.length, discoveredLinks.size)

        UrlProcessingResult(normalizedUrl, extractedMarkdown, discoveredLinks)
    }

    private suspend fun processPdfUrl(
        normalizedUrl: String,
        result: ContentTypeResult.Pdf
    ): UrlProcessingResult {
        // Convert PDF to markdown
        val markdown = pdfConversionService.convertPdfToMarkdown(result.bytes)

        // Cache the result
        webpageCacheService.cacheWebpage(
            url = normalizedUrl,
            markdown = markdown,
            html = null, // PDFs don't have HTML
            httpStatus = result.statusCode,
            httpReason = result.reasonPhrase,
            mimeType = result.mimeType
        )

        logger.debug("Processed PDF for URL: {} ({} chars)", normalizedUrl, markdown.length)

        // PDFs are terminal - no link discovery
        return UrlProcessingResult(normalizedUrl, markdown, emptyList())
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


