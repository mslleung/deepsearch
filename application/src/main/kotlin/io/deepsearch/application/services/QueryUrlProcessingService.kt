package io.deepsearch.application.services

import io.deepsearch.domain.exceptions.*
import io.deepsearch.domain.models.entities.WebpageImage
import io.deepsearch.domain.models.valueobjects.LinkSource
import io.deepsearch.domain.models.valueobjects.OcrLanguage
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.WebpageLink
import io.deepsearch.domain.proxy.ProxyConfiguration
import io.deepsearch.domain.repositories.IWebpageImageRepository
import io.deepsearch.domain.services.IImageStorageService
import io.deepsearch.domain.services.ImageToStore
import io.deepsearch.domain.services.INormalizeUrlService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import org.slf4j.LoggerFactory

interface IQueryUrlProcessingService {
    /**
     * Process a URL for query-time search. Discovers links relevant to the query
     * and performs agentic VLM search on HTML pages.
     *
     * Emits: [UrlProcessingEvent.LinkDiscoveryComplete], [UrlProcessingEvent.AgenticSearchComplete],
     * [UrlProcessingEvent.FileMarkdownExtractionComplete], [UrlProcessingEvent.PdfPreviewReady].
     */
    fun processUrlAsFlow(
        url: String,
        query: String,
        sessionId: QuerySessionId,
        ocrLanguage: OcrLanguage = OcrLanguage.DEFAULT,
        proxyConfig: ProxyConfiguration = ProxyConfiguration.None
    ): Flow<UrlProcessingEvent>
}

class QueryUrlProcessingService(
    private val normalizeUrlService: INormalizeUrlService,
    private val urlProcessingLockRegistry: UrlProcessingLockRegistry,
    private val httpContentTypeResolutionService: IHttpContentTypeResolutionService,
    private val agenticWebpageSearchService: IAgenticWebpageSearchService,
    private val browserPageResolver: IBrowserPageResolver,
    private val fileUrlProcessingService: IFileUrlProcessingService,
    private val webpageLinkDiscoveryService: IWebpageLinkDiscoveryService,
    private val imageStorageService: IImageStorageService,
    private val webpageImageRepository: IWebpageImageRepository
) : IQueryUrlProcessingService {

    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun processUrlAsFlow(
        url: String,
        query: String,
        sessionId: QuerySessionId,
        ocrLanguage: OcrLanguage,
        proxyConfig: ProxyConfiguration
    ): Flow<UrlProcessingEvent> = flow {
        logger.debug("Processing URL for query: {}", url)

        val normalizedUrl = normalizeUrlService.normalize(url) ?: url

        urlProcessingLockRegistry.withKeyLock(normalizedUrl) {
            logger.debug("Query session — skipping cache, proceeding to live processing: {}", normalizedUrl)

            when (val contentTypeResult = httpContentTypeResolutionService.resolve(normalizedUrl)) {
                is ContentTypeResult.Html -> {
                    logger.debug(
                        "Detected HTML content for: {} ({} bytes)",
                        normalizedUrl,
                        contentTypeResult.bodyBytes.size
                    )
                    processHtmlAsFlow(normalizedUrl, contentTypeResult.bodyBytes, query, sessionId, proxyConfig)
                        .collect { event -> emit(event) }
                }

                is ContentTypeResult.SupportedFile -> {
                    logger.debug(
                        "Detected supported file for: {} ({} bytes, type: {})",
                        normalizedUrl, contentTypeResult.bytes.size, contentTypeResult.mimeType
                    )
                    fileUrlProcessingService.processFileAsFlow(
                        normalizedUrl, contentTypeResult,
                        query, null, sessionId
                    ) { markdown ->
                        webpageLinkDiscoveryService.discoverRelevantLinksFromText(
                            query,
                            markdown,
                            normalizedUrl,
                            sessionId
                        )
                    }.collect { event -> emit(event) }
                }

                is ContentTypeResult.FileTooLarge -> {
                    throw FileTooLargeException(
                        normalizedUrl,
                        contentTypeResult.contentLength,
                        contentTypeResult.maxSizeBytes
                    )
                }

                is ContentTypeResult.Unsupported -> {
                    throw UnsupportedContentTypeException(normalizedUrl, contentTypeResult.contentType)
                }
            }
        }
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private suspend fun storeCapturedImages(
        captures: List<CapturedImage>
    ): Pair<List<String>, Map<String, String>> {
        if (captures.isEmpty()) return emptyList<String>() to emptyMap()

        val imagesToStore = captures.map { ImageToStore(it.bytesHash, it.bytes, it.mimeType) }
        val gcsPaths = imageStorageService.storeBatch(imagesToStore)
        logger.debug("Stored {} captured images in GCS", gcsPaths.size)

        val imageIds = mutableListOf<String>()
        val imageDescriptions = mutableMapOf<String, String>()
        val imagesToCache = mutableListOf<WebpageImage>()

        for (capture in captures) {
            val base64Hash = kotlin.io.encoding.Base64.encode(capture.bytesHash)
            val gcsPath = gcsPaths[base64Hash] ?: continue
            val urlSafeHash = base64Hash.replace("+", "-").replace("/", "_").trimEnd('=')
            val imageId = "img-$urlSafeHash"

            imageIds.add(imageId)
            imageDescriptions[imageId] = capture.relevance

            imagesToCache.add(
                WebpageImage(
                    imageBytesHash = capture.bytesHash,
                    gcsPath = gcsPath,
                    mimeType = capture.mimeType,
                    extractedText = capture.relevance
                )
            )
        }

        webpageImageRepository.batchUpsert(imagesToCache)
        logger.debug("Cached {} captured images in DB", imagesToCache.size)

        return imageIds to imageDescriptions
    }

    private fun processHtmlAsFlow(
        normalizedUrl: String,
        cachedHtmlBody: ByteArray,
        query: String,
        sessionId: QuerySessionId,
        proxyConfig: ProxyConfiguration
    ): Flow<UrlProcessingEvent> = channelFlow {
        browserPageResolver.withPageForCachedHtml(normalizedUrl, cachedHtmlBody, proxyConfig) { page ->
            val extractedHtml = page.getFullHtml()

            val linkDiscoveryFlow = flow {
                val discoveredLinks = webpageLinkDiscoveryService.discoverRelevantLinksByAgent(
                    query,
                    extractedHtml,
                    normalizedUrl,
                    sessionId
                )
                emit(UrlProcessingEvent.LinkDiscoveryComplete(normalizedUrl, discoveredLinks))
            }

            val contentFlow = flow {
                try {
                    page.waitForLoad()

                    val agenticResult = agenticWebpageSearchService.searchWithinPage(
                        page, normalizedUrl, query, sessionId
                    )
                    logger.debug("Agentic search complete for {}: success={}", normalizedUrl, agenticResult.success)

                    val (imageIds, imageDescriptions) = storeCapturedImages(agenticResult.capturedImages)

                    emit(
                        UrlProcessingEvent.AgenticSearchComplete(
                            url = normalizedUrl,
                            answer = agenticResult.answer,
                            evidence = agenticResult.evidence,
                            contentDate = agenticResult.contentDate,
                            observations = agenticResult.observations,
                            success = agenticResult.success,
                            imageIds = imageIds,
                            imageDescriptions = imageDescriptions
                        )
                    )

                    if (agenticResult.discoveredUrls.isNotEmpty()) {
                        val agenticLinks = agenticResult.discoveredUrls.map { discoveredUrl ->
                            WebpageLink(
                                url = discoveredUrl,
                                source = LinkSource.AGENTIC_NAVIGATION,
                                reason = "Discovered via agentic page interaction"
                            )
                        }
                        logger.info(
                            "Agentic search discovered {} new URLs from {}: {}",
                            agenticLinks.size, normalizedUrl, agenticLinks.map { it.url }
                        )
                        emit(UrlProcessingEvent.LinkDiscoveryComplete(normalizedUrl, agenticLinks))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw AgenticSearchException(normalizedUrl, e)
                }
            }

            merge(linkDiscoveryFlow, contentFlow)
                .onCompletion { cause ->
                    if (cause != null) logger.debug("Flow cancelled for {}: {}", normalizedUrl, cause.message)
                }
                .collect { event -> send(event) }
        }
    }

}
