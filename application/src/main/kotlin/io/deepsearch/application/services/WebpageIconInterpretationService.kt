package io.deepsearch.application.services

import io.deepsearch.application.services.batch.MediaData
import io.deepsearch.domain.agents.IMultiIconInterpreterAgent
import io.deepsearch.domain.agents.MultiIconInterpreterInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.entities.WebpageIcon
import io.deepsearch.domain.models.valueobjects.MediaHash
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.repositories.IWebpageIconRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.time.ExperimentalTime

/**
 * A pending batch request for media (icons or images), bundling the hash and request.
 */
data class PendingMediaBatchRequest(
    val hash: MediaHash,
    val request: io.deepsearch.domain.services.BatchContentRequest
)

/**
 * Result of preparing icon interpretation batch requests.
 * Contains cached results and batch requests for uncached icons.
 */
data class IconBatchPreparation(
    /** Map of icon hash to cached label (null if icon was not interpretable) */
    val cachedResults: Map<MediaHash, String?>,
    /** Pending requests for uncached icons (bundled with hash) */
    val pendingRequests: List<PendingMediaBatchRequest>
)

interface IWebpageIconInterpretationService {
    suspend fun interpretIcon(icon: IBrowserPage.Icon, sessionId: SessionId): String?
    suspend fun interpretIcons(icons: List<IBrowserPage.Icon>, sessionId: SessionId): List<String?>
    
    /**
     * Prepare batch requests for icon interpretation with cache check.
     * Returns cached results and batch requests for uncached icons only.
     * 
     * @param icons Map of media hash to icon data (bytes + mimeType)
     * @return Cached results and batch requests for uncached icons
     */
    suspend fun prepareBatchRequests(
        icons: Map<MediaHash, MediaData>,
        jobId: Long
    ): IconBatchPreparation
    
    /**
     * Process batch results and update cache.
     * 
     * @param results Map of icon hash -> interpreted label
     */
    suspend fun processBatchResults(results: Map<MediaHash, String?>)
}

class WebpageIconInterpretationService(
    private val multiIconInterpreterAgent: IMultiIconInterpreterAgent,
    private val webpageIconRepository: IWebpageIconRepository,
    private val tokenUsageService: ILlmTokenUsageService
) : IWebpageIconInterpretationService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    
    // ========== Batch API Methods ==========

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    override suspend fun prepareBatchRequests(
        icons: Map<MediaHash, MediaData>,
        jobId: Long
    ): IconBatchPreparation {
        if (icons.isEmpty()) {
            return IconBatchPreparation(emptyMap(), emptyList())
        }

        // Convert hash strings to byte arrays for cache lookup
        val hashBytesMap = icons.keys.associateWith { hash ->
            kotlin.io.encoding.Base64.decode(hash.value)
        }

        // Batch cache lookup
        val cachedIcons = webpageIconRepository.findByHashes(hashBytesMap.values.toList())
        val cachedResults = cachedIcons.associate { icon ->
            MediaHash(kotlin.io.encoding.Base64.encode(icon.imageBytesHash)) to icon.label
        }.toMutableMap()

        logger.debug("Found {} cached results for {} icons", cachedResults.size, icons.size)

        // Find uncached icons
        val uncachedIcons = icons.filterKeys { hash -> !cachedResults.containsKey(hash) }

        // Prepare batch requests for uncached icons
        val pendingRequests = mutableListOf<PendingMediaBatchRequest>()

        uncachedIcons.forEach { (hash, iconData) ->
            val request = multiIconInterpreterAgent.prepareBatchRequest(
                requestId = "$jobId-icon-${hash.value}",
                icons = listOf(
                    MultiIconInterpreterInput.IconItem(
                        bytes = iconData.bytes,
                        mimeType = io.deepsearch.domain.constants.ImageMimeType.fromValue(iconData.mimeType)
                    )
                )
            )
            pendingRequests.add(PendingMediaBatchRequest(hash = hash, request = request))
        }

        logger.debug("Prepared {} batch requests for uncached icons", pendingRequests.size)

        return IconBatchPreparation(
            cachedResults = cachedResults,
            pendingRequests = pendingRequests
        )
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class, kotlin.time.ExperimentalTime::class)
    override suspend fun processBatchResults(results: Map<MediaHash, String?>) {
        if (results.isEmpty()) return

        // Store results in cache
        val iconsToCache = results.map { (hash, label) ->
            val hashBytes = kotlin.io.encoding.Base64.decode(hash.value)
            WebpageIcon(
                imageBytesHash = hashBytes,
                label = label?.takeIf { it.isNotBlank() }
            )
        }

        webpageIconRepository.batchUpsert(iconsToCache)
        logger.debug("Cached {} icon interpretation results", iconsToCache.size)
    }
    
    // ========== Interactive Mode Methods ==========

    /**
     * Use an LLM to interpret the icon and return a textual representation of it.
     * Returns null if the icon cannot be interpreted into any meaningful text.
     * 
     * Note: Uses the multi-icon agent with a batch of 1 for consistency.
     * For better performance with multiple icons, use interpretIcons instead.
     */
    override suspend fun interpretIcon(icon: IBrowserPage.Icon, sessionId: SessionId): String? {
        return interpretIcons(listOf(icon), sessionId).firstOrNull()
    }

    /**
     * Use an LLM to interpret multiple icons in batch and return textual representations.
     * Returns null for icons that cannot be interpreted into any meaningful text.
     * Efficiently processes multiple icons by:
     * - Pre-computing hashes once for all icons
     * - Batch cache lookup (single DB query)
     * - Batching uncached icons for LLM processing
     * - Using multi-icon agent that processes multiple icons per LLM call
     * Results are cached to avoid reprocessing the same icons.
     */
    @OptIn(ExperimentalTime::class)
    override suspend fun interpretIcons(icons: List<IBrowserPage.Icon>, sessionId: SessionId): List<String?> {
        if (icons.isEmpty()) {
            return emptyList()
        }

        // Pre-compute all hashes once (previously computed 3x per icon)
        val iconHashes = icons.map { icon ->
            MessageDigest.getInstance("SHA-256").digest(icon.bytes)
        }
        val hashToBase64 = iconHashes.associateWith { Base64.encode(it) }

        // Batch cache lookup (single DB query instead of N queries)
        val cachedIcons = webpageIconRepository.findByHashes(iconHashes)
        val cachedResults = cachedIcons.associate { icon ->
            Base64.encode(icon.imageBytesHash) to icon.label?.takeIf { it.isNotBlank() }
        }.toMutableMap()
        
        logger.debug("Found {} cached results for {} icons", cachedResults.size, icons.size)

        // Find uncached icons by checking which hashes are not in cached results
        val uncachedIconsWithHashes = icons.zip(iconHashes).filter { (_, hash) ->
            !cachedResults.containsKey(hashToBase64[hash])
        }

        // Process uncached icons
        if (uncachedIconsWithHashes.isNotEmpty()) {
            logger.debug("Processing {} uncached icons", uncachedIconsWithHashes.size)
            
            val interpreterAgentOutput = multiIconInterpreterAgent.generate(
                MultiIconInterpreterInput(
                    icons = uncachedIconsWithHashes.map { (icon, _) ->
                        MultiIconInterpreterInput.IconItem(
                            bytes = icon.bytes,
                            mimeType = icon.mimeType
                        )
                    }
                )
            )

            // Record token usage
            tokenUsageService.recordTokenUsage(
                sessionId = sessionId,
                agentName = "MultiIconInterpreterAgent",
                modelName = interpreterAgentOutput.tokenUsage.modelName,
                promptTokens = interpreterAgentOutput.tokenUsage.promptTokens,
                outputTokens = interpreterAgentOutput.tokenUsage.outputTokens,
                totalTokens = interpreterAgentOutput.tokenUsage.totalTokens
            )

            // Cache results using pre-computed hashes
            val iconsToCache = uncachedIconsWithHashes.mapIndexed { index, (_, hash) ->
                val label = interpreterAgentOutput.interpretations[index].label?.takeIf { it.isNotBlank() }
                cachedResults[hashToBase64[hash]!!] = label
                WebpageIcon(
                    imageBytesHash = hash,
                    label = label
                )
            }
            webpageIconRepository.batchUpsert(iconsToCache)
        }

        // Return results in original order using pre-computed hashes
        return iconHashes.map { hash -> 
            cachedResults[hashToBase64[hash]]
        }
    }
}
