package io.deepsearch.application.services

import io.deepsearch.domain.agents.IMultiIconInterpreterAgent
import io.deepsearch.domain.agents.MultiIconInterpreterInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.entities.WebpageIcon
import io.deepsearch.domain.repositories.IWebpageIconRepository
import java.security.MessageDigest

interface IWebpageIconInterpretationService {
    suspend fun interpretIcon(icon: IBrowserPage.Icon): String?
}

class WebpageIconInterpretationService(
    private val multiIconInterpreterAgent: IMultiIconInterpreterAgent,
    private val webpageIconRepository: IWebpageIconRepository
) : IWebpageIconInterpretationService {

    /**
     * Use an LLM to interpret the icon and return a textual representation of it.
     * Returns null if the icon cannot be interpreted into any meaningful text.
     * 
     * Note: Uses the multi-icon agent with a batch of 1 for consistency.
     * For better performance with multiple icons, consider using the multi-icon agent directly.
     */
    override suspend fun interpretIcon(icon: IBrowserPage.Icon): String? {
        val bytesHash = MessageDigest.getInstance("SHA-256").digest(icon.bytes)

        val existing = webpageIconRepository.findByHash(bytesHash)
        if (existing != null) {
            return existing.label?.takeIf { it.isNotBlank() }
        }

        // Use multi-icon agent with a batch of 1
        val interpreterAgentOutput = multiIconInterpreterAgent.generate(
            MultiIconInterpreterInput(
                icons = listOf(
                    MultiIconInterpreterInput.IconItem(
                        bytes = icon.bytes,
                        mimeType = icon.mimeType
                    )
                )
            )
        )
        val label = interpreterAgentOutput.interpretations.firstOrNull()?.label?.takeIf { it.isNotBlank() }

        webpageIconRepository.upsert(
            WebpageIcon(
                imageBytesHash = bytesHash,
                label = label
            )
        )

        return label
    }
}
