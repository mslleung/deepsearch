package io.deepsearch.application.services

import io.deepsearch.domain.agents.IIconInterpreterAgent
import io.deepsearch.domain.agents.IconInterpreterInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.entities.WebpageIcon
import io.deepsearch.domain.repositories.IWebpageIconRepository
import java.security.MessageDigest

interface IWebpageIconInterpretationService {
    suspend fun interpretIcon(icon: IBrowserPage.Icon): String?
}

class WebpageIconInterpretationService(
    private val iconInterpreterAgent: IIconInterpreterAgent,
    private val webpageIconRepository: IWebpageIconRepository
) : IWebpageIconInterpretationService {

    /**
     * Use an LLM to interpret the icon and return a textual representation of it.
     * Returns null if the icon cannot be interpreted into any meaningful text.
     */
    override suspend fun interpretIcon(icon: IBrowserPage.Icon): String? {
        val bytesHash = MessageDigest.getInstance("SHA-256").digest(icon.bytes)

        val existing = webpageIconRepository.findByHash(bytesHash)
        if (existing != null) {
            return existing.label?.takeIf { it.isNotBlank() }
        }

        val interpreterAgentOutput = iconInterpreterAgent.generate(
            IconInterpreterInput(bytes = icon.bytes, mimeType = icon.mimeType)
        )
        val label = interpreterAgentOutput.label?.takeIf { it.isNotBlank() }

        webpageIconRepository.upsert(
            WebpageIcon(
                imageBytesHash = bytesHash,
                label = label
            )
        )

        return label
    }
}
