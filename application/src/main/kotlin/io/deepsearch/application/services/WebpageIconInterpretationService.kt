package io.deepsearch.application.services

import io.deepsearch.domain.agents.IMultiIconInterpreterAgent
import io.deepsearch.domain.agents.MultiIconInterpreterInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.entities.WebpageIcon
import io.deepsearch.domain.repositories.IWebpageIconRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import kotlin.io.encoding.Base64

interface IWebpageIconInterpretationService {
    suspend fun interpretIcon(icon: IBrowserPage.Icon): String?
    suspend fun interpretIcons(icons: List<IBrowserPage.Icon>): List<String?>
}

class WebpageIconInterpretationService(
    private val multiIconInterpreterAgent: IMultiIconInterpreterAgent,
    private val webpageIconRepository: IWebpageIconRepository
) : IWebpageIconInterpretationService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Use an LLM to interpret the icon and return a textual representation of it.
     * Returns null if the icon cannot be interpreted into any meaningful text.
     * 
     * Note: Uses the multi-icon agent with a batch of 1 for consistency.
     * For better performance with multiple icons, use interpretIcons instead.
     */
    override suspend fun interpretIcon(icon: IBrowserPage.Icon): String? {
        return interpretIcons(listOf(icon)).firstOrNull()
    }

    /**
     * Use an LLM to interpret multiple icons in batch and return textual representations.
     * Returns null for icons that cannot be interpreted into any meaningful text.
     * Efficiently processes multiple icons by:
     * - Checking cache first for all icons
     * - Batching uncached icons for LLM processing
     * - Using multi-icon agent that processes multiple icons per LLM call
     * Results are cached to avoid reprocessing the same icons.
     */
    override suspend fun interpretIcons(icons: List<IBrowserPage.Icon>): List<String?> {
        if (icons.isEmpty()) {
            return emptyList()
        }

        // Check cache for all icons
        val cachedResults = mutableMapOf<String, String?>()
        val uncachedIcons = mutableListOf<IBrowserPage.Icon>()
        
        icons.forEach { icon ->
            val bytesHash = MessageDigest.getInstance("SHA-256").digest(icon.bytes)
            val existing = webpageIconRepository.findByHash(bytesHash)
            if (existing != null) {
                logger.debug("Found cached result for icon")
                cachedResults[Base64.encode(bytesHash)] = existing.label?.takeIf { it.isNotBlank() }
            } else {
                uncachedIcons.add(icon)
            }
        }

        // Process uncached icons
        if (uncachedIcons.isNotEmpty()) {
            logger.debug("Processing {} uncached icons", uncachedIcons.size)
            
            val interpreterAgentOutput = multiIconInterpreterAgent.generate(
                MultiIconInterpreterInput(
                    icons = uncachedIcons.map { icon ->
                        MultiIconInterpreterInput.IconItem(
                            bytes = icon.bytes,
                            mimeType = icon.mimeType
                        )
                    }
                )
            )

            // Cache results
            uncachedIcons.forEachIndexed { index, icon ->
                val bytesHash = MessageDigest.getInstance("SHA-256").digest(icon.bytes)
                val label = interpreterAgentOutput.interpretations[index].label?.takeIf { it.isNotBlank() }
                webpageIconRepository.upsert(
                    WebpageIcon(
                        imageBytesHash = bytesHash,
                        label = label
                    )
                )
                cachedResults[Base64.encode(bytesHash)] = label
            }
        }

        // Return results in original order
        return icons.map { icon -> 
            val bytesHash = MessageDigest.getInstance("SHA-256").digest(icon.bytes)
            cachedResults[Base64.encode(bytesHash)]
        }
    }
}
