package io.deepsearch.application.services

import io.deepsearch.application.services.batch.PageHtmlWithBoundingBoxes
import io.deepsearch.domain.agents.IVisualIdentificationAgent
import io.deepsearch.domain.agents.TableIdentification
import io.deepsearch.domain.agents.VisualIdentificationInput
import io.deepsearch.domain.agents.VisualIdentificationOutput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.entities.VisionDetectionCache
import io.deepsearch.domain.models.valueobjects.BatchUrlStateId
import io.deepsearch.domain.models.valueobjects.SemanticElements
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.repositories.IVisionDetectionCacheRepository
import io.deepsearch.domain.services.BatchContentRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * Combined result of visual identification containing both semantic elements and tables.
 */
data class VisualIdentificationResult(
    val semanticElements: SemanticElements,
    val tables: List<TableIdentification>
)

/**
 * Result of preparing visual identification batch requests.
 */
data class VisualIdentificationBatchPreparation(
    /** Map of URL state ID to cached results (if found in cache) */
    val cachedResults: Map<BatchUrlStateId, VisualIdentificationResult>,
    /** Pending requests for uncached pages */
    val pendingRequests: List<PendingVisualBatchRequest>
)

/**
 * A pending batch request for visual identification.
 */
data class PendingVisualBatchRequest(
    val urlStateId: BatchUrlStateId,
    val request: BatchContentRequest,
    val htmlWithIds: String
)

/**
 * Service interface for combined visual identification (semantic elements + tables).
 * 
 * This service merges the functionality of ISemanticIdentificationService and 
 * ITableIdentificationService for vision-based detection, reducing LLM calls from 2 to 1.
 * 
 * Note: This service only handles vision-based detection. Hidden container table detection
 * still requires separate processing via ITableIdentificationService.
 */
interface IVisualIdentificationService {
    /**
     * Identifies both semantic elements and tables in a single vision-based LLM call.
     * 
     * Benefits over separate calls:
     * - ~30-50% reduction in LLM latency (single call vs parallel calls with overhead)
     * - ~40% reduction in token usage (image tokens sent only once)
     * 
     * @param sessionId Session ID for token tracking
     * @param pageSnapshot Pre-captured page snapshot containing HTML and bounding boxes
     * @param screenshot Full-page screenshot for vision-based detection
     * @return Combined result with semantic elements and tables
     */
    suspend fun identifyVisualElements(
        sessionId: SessionId,
        pageSnapshot: IBrowserPage.PageSnapshotWithMetadata,
        screenshot: IBrowserPage.Screenshot
    ): VisualIdentificationResult
    
    // ========== Batch API Methods ==========
    
    /**
     * Prepare batch requests for visual identification with cache check.
     * 
     * @param pages Map of URL state ID -> page HTML with bounding boxes
     * @param jobId Batch job ID for request ID generation
     * @return Cached results and batch requests for uncached pages
     */
    suspend fun prepareBatchRequests(
        pages: Map<BatchUrlStateId, PageHtmlWithBoundingBoxes>,
        jobId: Long
    ): VisualIdentificationBatchPreparation
    
    /**
     * Parse batch response and return visual identification result.
     * 
     * @param responseText JSON response from batch API
     * @param htmlWithIds HTML with injected IDs
     * @param boundingBoxes Element bounding boxes for vision IoU mapping
     * @param pageWidth Page width for vision mapping
     * @param pageHeight Page height for vision mapping
     * @return Combined result with semantic elements and tables
     */
    fun parseBatchResponse(
        responseText: String,
        htmlWithIds: String,
        boundingBoxes: Map<String, IBrowserPage.BoundingBox>,
        pageWidth: Double,
        pageHeight: Double
    ): VisualIdentificationResult
}

/**
 * Cached vision detection result for serialization.
 * Stores the full agent output so we can return it on cache hit.
 */
@Serializable
private data class CachedVisionResult(
    val semanticElements: CachedSemanticElements,
    val tables: List<CachedTableIdentification>
)

@Serializable
private data class CachedSemanticElements(
    val headerDataId: String? = null,
    val headerNote: String? = null,
    val footerDataId: String? = null,
    val footerNote: String? = null,
    val navSidebarDataId: String? = null,
    val navSidebarNote: String? = null,
    val breadcrumbDataId: String? = null,
    val breadcrumbNote: String? = null,
    val cookieBannerDataId: String? = null,
    val cookieBannerNote: String? = null
)

@Serializable
private data class CachedTableIdentification(
    val cssSelector: String,
    val dataId: String,
    val auxiliaryInfo: String,
    val containsMedia: Boolean
)

/**
 * Implementation of combined visual identification service.
 * 
 * This service delegates to VisualIdentificationAgent for the actual LLM call,
 * which detects both semantic elements and tables in a single vision-based request.
 * 
 * Caching is implemented at the service layer using a hash of screenshot bytes + structural HTML.
 */
class VisualIdentificationService(
    private val visualIdentificationAgent: IVisualIdentificationAgent,
    private val tokenUsageService: ILlmTokenUsageService,
    private val visionDetectionCacheRepository: IVisionDetectionCacheRepository
) : IVisualIdentificationService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    
    private val cacheJson = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }

    // ========== Caching Helper Methods ==========

    /**
     * Strips data-ds-id attributes from HTML to create a stable structural hash.
     * data-ds-id values change on every page load, so we remove them before hashing.
     */
    private fun stripDataDsId(html: String): String {
        return html.replace(Regex("""data-ds-id="[^"]*""""), "")
    }

    /**
     * Computes SHA-256 hash of screenshot bytes + structural HTML.
     * Used as cache key for vision detection results.
     */
    private fun computeVisionContentHash(screenshotBytes: ByteArray, html: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(screenshotBytes)
        digest.update(stripDataDsId(html).toByteArray(Charsets.UTF_8))
        return digest.digest()
    }

    private fun agentOutputToCached(output: VisualIdentificationOutput): CachedVisionResult {
        return CachedVisionResult(
            semanticElements = CachedSemanticElements(
                headerDataId = output.semanticElements.header?.dataId,
                headerNote = output.semanticElements.header?.note,
                footerDataId = output.semanticElements.footer?.dataId,
                footerNote = output.semanticElements.footer?.note,
                navSidebarDataId = output.semanticElements.navSidebar?.dataId,
                navSidebarNote = output.semanticElements.navSidebar?.note,
                breadcrumbDataId = output.semanticElements.breadcrumb?.dataId,
                breadcrumbNote = output.semanticElements.breadcrumb?.note,
                cookieBannerDataId = output.semanticElements.cookieBanner?.dataId,
                cookieBannerNote = output.semanticElements.cookieBanner?.note
            ),
            tables = output.tables.map { table ->
                CachedTableIdentification(
                    cssSelector = table.cssSelector,
                    dataId = table.dataId,
                    auxiliaryInfo = table.auxiliaryInfo,
                    containsMedia = table.containsMedia
                )
            }
        )
    }

    private fun cachedToResult(cached: CachedVisionResult): VisualIdentificationResult {
        return VisualIdentificationResult(
            semanticElements = SemanticElements(
                header = cached.semanticElements.headerDataId?.let { 
                    io.deepsearch.domain.models.valueobjects.IdentifiedElement(it, cached.semanticElements.headerNote ?: "")
                },
                footer = cached.semanticElements.footerDataId?.let {
                    io.deepsearch.domain.models.valueobjects.IdentifiedElement(it, cached.semanticElements.footerNote ?: "")
                },
                navSidebar = cached.semanticElements.navSidebarDataId?.let {
                    io.deepsearch.domain.models.valueobjects.IdentifiedElement(it, cached.semanticElements.navSidebarNote ?: "")
                },
                breadcrumb = cached.semanticElements.breadcrumbDataId?.let {
                    io.deepsearch.domain.models.valueobjects.IdentifiedElement(it, cached.semanticElements.breadcrumbNote ?: "")
                },
                cookieBanner = cached.semanticElements.cookieBannerDataId?.let {
                    io.deepsearch.domain.models.valueobjects.IdentifiedElement(it, cached.semanticElements.cookieBannerNote ?: "")
                },
                adBanners = emptyList(),
                popups = emptyList()
            ),
            tables = cached.tables.map { table ->
                TableIdentification(
                    cssSelector = table.cssSelector,
                    dataId = table.dataId,
                    auxiliaryInfo = table.auxiliaryInfo,
                    containsMedia = table.containsMedia
                )
            }
        )
    }

    // ========== Interactive Mode Methods ==========

    override suspend fun identifyVisualElements(
        sessionId: SessionId,
        pageSnapshot: IBrowserPage.PageSnapshotWithMetadata,
        screenshot: IBrowserPage.Screenshot
    ): VisualIdentificationResult {
        logger.debug(
            "Starting combined visual identification: HTML={} bytes, screenshot={} bytes",
            pageSnapshot.html.length, screenshot.bytes.size
        )

        // Check cache first
        val contentHash = computeVisionContentHash(screenshot.bytes, pageSnapshot.html)
        val cached = visionDetectionCacheRepository.findByHash(contentHash)
        
        if (cached != null) {
            logger.debug("Vision detection cache HIT")
            val cachedResult = cacheJson.decodeFromString<CachedVisionResult>(cached.visionResponseJson)
            return cachedToResult(cachedResult)
        }
        
        logger.debug("Vision detection cache MISS - calling LLM")

        val agentOutput = visualIdentificationAgent.generate(
            VisualIdentificationInput(
                pageSnapshot = pageSnapshot,
                screenshot = screenshot
            )
        )

        // Record token usage
        tokenUsageService.recordTokenUsage(
            sessionId = sessionId,
            agentName = "VisualIdentificationAgent",
            modelName = agentOutput.tokenUsage.modelName,
            promptTokens = agentOutput.tokenUsage.promptTokens,
            outputTokens = agentOutput.tokenUsage.outputTokens,
            totalTokens = agentOutput.tokenUsage.totalTokens
        )

        // Cache the result
        val cachedResult = agentOutputToCached(agentOutput)
        visionDetectionCacheRepository.upsert(
            VisionDetectionCache(
                contentHash = contentHash,
                visionResponseJson = cacheJson.encodeToString(cachedResult)
            )
        )

        logger.debug(
            "Visual identification complete: {} semantic elements, {} tables, {} tokens",
            countSemanticElements(agentOutput.semanticElements),
            agentOutput.tables.size,
            agentOutput.tokenUsage.totalTokens
        )

        return VisualIdentificationResult(
            semanticElements = agentOutput.semanticElements,
            tables = agentOutput.tables
        )
    }

    // ========== Batch API Methods ==========

    override suspend fun prepareBatchRequests(
        pages: Map<BatchUrlStateId, PageHtmlWithBoundingBoxes>,
        jobId: Long
    ): VisualIdentificationBatchPreparation {
        if (pages.isEmpty()) {
            return VisualIdentificationBatchPreparation(emptyMap(), emptyList())
        }

        // Note: Visual identification doesn't use caching since it combines two types
        // that may have different cache invalidation patterns. For batch processing,
        // we process all pages.
        val pendingRequests = mutableListOf<PendingVisualBatchRequest>()

        for ((urlStateId, pageData) in pages) {
            // Skip pages without screenshots (visual identification requires screenshot)
            if (pageData.screenshotBase64 == null || pageData.screenshotMimeType == null) {
                logger.debug("Skipping page {} - no screenshot available", urlStateId.value)
                continue
            }

            // Calculate page dimensions from bounding boxes
            val pageWidth = pageData.boundingBoxes.values.maxOfOrNull { it.right } ?: 1920.0
            val pageHeight = pageData.boundingBoxes.values.maxOfOrNull { it.bottom } ?: 1080.0

            val batchRequest = visualIdentificationAgent.prepareBatchRequest(
                requestId = "$jobId-visual-${urlStateId.value}",
                html = pageData.html,
                screenshotBase64 = pageData.screenshotBase64,
                screenshotMimeType = pageData.screenshotMimeType,
                boundingBoxes = pageData.boundingBoxes,
                pageWidth = pageWidth,
                pageHeight = pageHeight
            )

            pendingRequests.add(
                PendingVisualBatchRequest(
                    urlStateId = urlStateId,
                    request = batchRequest.request,
                    htmlWithIds = batchRequest.htmlWithIds
                )
            )
        }

        logger.debug("Visual ID batch: {} requests prepared", pendingRequests.size)

        return VisualIdentificationBatchPreparation(
            cachedResults = emptyMap(),
            pendingRequests = pendingRequests
        )
    }

    override fun parseBatchResponse(
        responseText: String,
        htmlWithIds: String,
        boundingBoxes: Map<String, IBrowserPage.BoundingBox>,
        pageWidth: Double,
        pageHeight: Double
    ): VisualIdentificationResult {
        val output = visualIdentificationAgent.parseBatchResponse(
            responseText, htmlWithIds, boundingBoxes, pageWidth, pageHeight
        )
        return VisualIdentificationResult(
            semanticElements = output.semanticElements,
            tables = output.tables
        )
    }

    // ========== Helper Methods ==========

    private fun countSemanticElements(elements: SemanticElements): Int {
        var count = 0
        if (elements.header != null) count++
        if (elements.footer != null) count++
        if (elements.navSidebar != null) count++
        if (elements.breadcrumb != null) count++
        if (elements.cookieBanner != null) count++
        count += elements.adBanners.size
        count += elements.popups.size
        return count
    }
}
