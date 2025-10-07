package io.deepsearch.application.services

import io.deepsearch.domain.agents.IImageTextExtractionAgent
import io.deepsearch.domain.agents.ImageTextExtractionInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.entities.WebpageImage
import io.deepsearch.domain.repositories.IWebpageImageRepository
 
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.MessageDigest

interface IWebpageImageTextExtractionService {
    suspend fun extractTextFromImage(image: IBrowserPage.WebImage): String?
}

class WebpageImageTextExtractionService(
    private val imageTextExtractionAgent: IImageTextExtractionAgent,
    private val webpageImageRepository: IWebpageImageRepository
) : IWebpageImageTextExtractionService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Extract text from an image using Tesseract OCR + LLM.
     * First checks if the image contains any text using Tesseract.
     * If text is detected, uses LLM to extract it with proper formatting (including tables).
     * Results are cached to avoid reprocessing the same image.
     */
    override suspend fun extractTextFromImage(image: IBrowserPage.WebImage): String? {
        // Check cache first
        val existing = webpageImageRepository.findByHash(image.bytesHash)
        if (existing != null) {
            logger.debug("Found cached result for image")
            return existing.extractedText?.takeIf { it.isNotBlank() }
        }

        val hasText = image.containsText()
        
        if (!hasText) {
            logger.debug("No text detected in image by OCR, skipping LLM")
            // Cache the result as having no text
            webpageImageRepository.upsert(
                WebpageImage(
                    imageBytesHash = image.bytesHash,
                    extractedText = null
                )
            )
            return null
        }

        logger.debug("Text detected by OCR, using LLM for extraction")
        
        // Use LLM to extract text with proper formatting
        val extractionOutput = imageTextExtractionAgent.generate(
            ImageTextExtractionInput(bytes = image.bytes, mimeType = image.mimeType)
        )
        val extractedText = extractionOutput.extractedText?.takeIf { it.isNotBlank() }

        // Cache the result
        webpageImageRepository.upsert(
            WebpageImage(
                imageBytesHash = image.bytesHash,
                extractedText = extractedText
            )
        )

        return extractedText
    }
}
