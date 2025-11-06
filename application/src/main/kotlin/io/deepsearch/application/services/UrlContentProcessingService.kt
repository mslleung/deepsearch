package io.deepsearch.application.services

import io.deepsearch.domain.browser.IBrowserRuntimePool
import io.deepsearch.domain.models.valueobjects.WebpageLink
import io.deepsearch.domain.models.valueobjects.UrlFailureReason
import io.deepsearch.domain.exceptions.UrlProcessingException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.serialization.SerializationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import io.deepsearch.application.services.IUrlContentProcessingService.*
import java.io.IOException
import java.net.UnknownHostException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import java.util.concurrent.TimeoutException

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
            val wasCached: Boolean
        ) : UrlProcessingEvent
    }

    /**
     * Process a URL and discover links relevant to the provided query.
     * Emits LinkDiscoveryComplete event first, then MarkdownExtractionComplete event.
     */
    fun processUrlAsFlow(
        url: String,
        query: String
    ): Flow<UrlProcessingEvent>

    /**
     * Process a URL and discover all links on the page (query-agnostic).
     * Emits LinkDiscoveryComplete event first, then MarkdownExtractionComplete event.
     */
    fun processUrlAsFlow(
        url: String
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
        query: String
    ): Flow<UrlProcessingEvent> {
        return processInternalAsFlow(url) { html ->
            webpageLinkDiscoveryService.discoverRelevantLinksByAgent(query, html, url)
        }
    }

    override fun processUrlAsFlow(
        url: String
    ): Flow<UrlProcessingEvent> {
        return processInternalAsFlow(url) { html ->
            webpageLinkDiscoveryService.discoverAllLinks(html, url)
        }
    }

    private fun processInternalAsFlow(
        url: String,
        discoverLinks: suspend (html: String) -> List<WebpageLink>
    ): Flow<UrlProcessingEvent> = flow {
        logger.debug("Processing URL: {}", url)

        val normalizedUrl = normalizeUrlService.normalize(url) ?: url

        try {
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
                        processHtmlUrlAsFlow(normalizedUrl, discoverLinks)
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
                        // Throw exception for unsupported content
                        throw UrlProcessingException(
                            url = normalizedUrl,
                            reason = UrlFailureReason.UNSUPPORTED_CONTENT_TYPE,
                            message = "Unsupported content type: ${contentTypeResult.contentType}",
                            cause = IllegalArgumentException("Unsupported content type")
                        )
                    }
                    is ContentTypeResult.Failed -> {
                        logger.debug("Failed to resolve content type for {}: {} {}", normalizedUrl, contentTypeResult.statusCode, contentTypeResult.reasonPhrase)
                        cacheFailedRequest(normalizedUrl, contentTypeResult)
                        // Map HTTP status codes to failure reasons and throw exception
                        val reason = when (contentTypeResult.statusCode) {
                            in 400..499 -> UrlFailureReason.HTTP_4XX_CLIENT_ERROR
                            in 500..599 -> UrlFailureReason.HTTP_5XX_SERVER_ERROR
                            else -> UrlFailureReason.FAILED_OTHER
                        }
                        throw UrlProcessingException(
                            url = normalizedUrl,
                            reason = reason,
                            message = "HTTP ${contentTypeResult.statusCode}: ${contentTypeResult.reasonPhrase}",
                            cause = IOException("HTTP error")
                        )
                    }
                }
            }
        } catch (e: UrlProcessingException) {
            // Re-throw UrlProcessingException as-is (already has reason and url)
            throw e
        } catch (e: IOException) {
            logger.error("I/O error processing {}: {}", normalizedUrl, e.message)
            throw UrlProcessingException(
                url = normalizedUrl,
                reason = mapIOException(e),
                message = e.message ?: "I/O error",
                cause = e
            )
        } catch (e: SerializationException) {
            logger.error("Serialization error processing {}: {}", normalizedUrl, e.message)
            throw UrlProcessingException(
                url = normalizedUrl,
                reason = UrlFailureReason.PARSING_ERROR,
                message = e.message ?: "Serialization error",
                cause = e
            )
        } catch (e: TimeoutException) {
            logger.error("Timeout processing {}: {}", normalizedUrl, e.message)
            throw UrlProcessingException(
                url = normalizedUrl,
                reason = UrlFailureReason.NETWORK_TIMEOUT,
                message = e.message ?: "Timeout",
                cause = e
            )
        } catch (e: Exception) {
            logger.error("Unexpected error processing {}: {}", normalizedUrl, e.message, e)
            throw UrlProcessingException(
                url = normalizedUrl,
                reason = mapGenericException(e),
                message = e.message ?: "Unexpected error",
                cause = e
            )
        }
    }

    private fun handleCachedResultAsFlow(
        originalUrl: String,
        cached: io.deepsearch.domain.models.entities.WebpageMarkdown,
        discoverLinks: suspend (html: String) -> List<WebpageLink>
    ): Flow<UrlProcessingEvent> = flow {
        if (cached.httpStatus != null && cached.httpStatus !in 200..299) {
            logger.debug("Cached failure for URL: {} (status: {})", originalUrl, cached.httpStatus)
            emit(UrlProcessingEvent.MarkdownExtractionComplete(originalUrl, "", wasCached = true))
            return@flow
        }

        val cachedHtml = cached.html
        val links = if (cachedHtml != null) discoverLinks(cachedHtml) else emptyList()

        // Emit links first, then markdown (consistent with non-cached flow)
        logger.debug("Emitting {} cached links for URL: {}", links.size, originalUrl)
        emit(UrlProcessingEvent.LinkDiscoveryComplete(originalUrl, links))
        
        logger.debug("Emitting cached markdown for URL: {} ({} chars)", originalUrl, cached.markdown?.length ?: 0)
        emit(UrlProcessingEvent.MarkdownExtractionComplete(originalUrl, cached.markdown ?: "", wasCached = true))
    }

    private fun processHtmlUrlAsFlow(
        normalizedUrl: String,
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

                    emit(UrlProcessingEvent.MarkdownExtractionComplete(normalizedUrl, extractedMarkdown, wasCached = false))
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
                    
            } catch (e: Exception) {
                logger.debug("Browser operation failed for {}: {}", normalizedUrl, e.message)
                throw e
            } finally {
                browser.close()
            }
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
        emit(UrlProcessingEvent.MarkdownExtractionComplete(normalizedUrl, markdown, wasCached = false))
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

    /**
     * Maps Playwright-specific exceptions to UrlFailureReason.
     * Uses exception type and Playwright error codes (net::ERR_*) for precise categorization.
     */
    private fun mapPlaywrightException(e: Exception): UrlFailureReason {
        // Check if it's a Playwright TimeoutError
        if (e.javaClass.simpleName == "TimeoutError") {
            return UrlFailureReason.NETWORK_TIMEOUT
        }

        // Parse Playwright network error codes from exception message
        val message = e.message ?: ""
        return when {
            message.contains("net::ERR_NAME_NOT_RESOLVED") -> UrlFailureReason.DNS_RESOLUTION_FAILED
            message.contains("net::ERR_CONNECTION_REFUSED") -> UrlFailureReason.CONNECTION_REFUSED
            message.contains("net::ERR_SSL") || message.contains("net::ERR_CERT") -> UrlFailureReason.SSL_HANDSHAKE_FAILED
            message.contains("net::ERR_ABORTED") -> UrlFailureReason.BROWSER_NAVIGATION_FAILED
            message.contains("net::ERR_TIMED_OUT") -> UrlFailureReason.NETWORK_TIMEOUT
            message.contains("net::ERR_TOO_MANY_REDIRECTS") -> UrlFailureReason.HTTP_REDIRECT_LOOP
            else -> UrlFailureReason.BROWSER_NAVIGATION_FAILED
        }
    }

    /**
     * Maps Java I/O exceptions to UrlFailureReason.
     * Uses specific exception types for precise error categorization.
     */
    private fun mapIOException(e: IOException): UrlFailureReason {
        return when (e) {
            is UnknownHostException -> UrlFailureReason.DNS_RESOLUTION_FAILED
            is ConnectException -> UrlFailureReason.CONNECTION_REFUSED
            is SocketTimeoutException -> UrlFailureReason.NETWORK_TIMEOUT
            is SSLException -> UrlFailureReason.SSL_HANDSHAKE_FAILED
            else -> UrlFailureReason.FAILED_OTHER
        }
    }

    /**
     * Maps generic exceptions to UrlFailureReason.
     * This is a catch-all function used when more specific exception types aren't available.
     */
    private fun mapGenericException(e: Exception): UrlFailureReason {
        return when (e) {
            is TimeoutException -> UrlFailureReason.NETWORK_TIMEOUT
            is SerializationException -> UrlFailureReason.PARSING_ERROR
            is IOException -> mapIOException(e)
            else -> {
                // Check if it's a Playwright exception by class name
                if (e.javaClass.name.startsWith("com.microsoft.playwright")) {
                    mapPlaywrightException(e)
                } else {
                    UrlFailureReason.FAILED_OTHER
                }
            }
        }
    }
}


