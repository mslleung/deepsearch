package io.deepsearch.application.services

import io.deepsearch.domain.agents.IMultiImageTextExtractionAgent
import io.deepsearch.domain.agents.MultiImageTextExtractionInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.entities.WebpageImage
import io.deepsearch.domain.repositories.IWebpageImageRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.io.encoding.Base64
import kotlin.time.ExperimentalTime

interface IWebpageImageTextExtractionService {
    suspend fun extractTextFromImage(image: IBrowserPage.WebImage): String?
    suspend fun extractTextFromImages(images: List<IBrowserPage.WebImage>): List<String?>
}

class WebpageImageTextExtractionService(
    private val multiImageTextExtractionAgent: IMultiImageTextExtractionAgent,
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
        return extractTextFromImages(listOf(image)).firstOrNull()
    }

    /**
     * Extract text from multiple images in batch using Tesseract OCR + LLM.
     * Efficiently processes multiple images by:
     * - Checking cache first for all images
     * - Batching uncached images for LLM processing
     * - Using multi-image agent that processes up to 50 images per LLM call
     * Results are cached to avoid reprocessing the same images.
     */
    @OptIn(ExperimentalTime::class)
    override suspend fun extractTextFromImages(images: List<IBrowserPage.WebImage>): List<String?> {
        if (images.isEmpty()) {
            return emptyList()
        }

        // Check cache for all images
        val cachedResults = mutableMapOf<String, String?>()
        val uncachedImages = mutableListOf<IBrowserPage.WebImage>()
        
        images.forEach { image ->
            val existing = webpageImageRepository.findByHash(image.bytesHash)
            if (existing != null) {
                logger.debug("Found cached result for image")
                cachedResults[Base64.encode(image.bytesHash)] = existing.extractedText?.takeIf { it.isNotBlank() }
            } else {
                uncachedImages.add(image)
            }
        }

        // Process uncached images
        if (uncachedImages.isNotEmpty()) {
            logger.debug("Processing {} uncached images", uncachedImages.size)
            
            // Filter images by OCR text detection
            val imagesWithText = mutableListOf<IBrowserPage.WebImage>()
            val imagesWithoutText = mutableListOf<IBrowserPage.WebImage>()
            
			coroutineScope {
				val ocrResults = uncachedImages.map { image ->
					async { image to image.containsText() }
				}.awaitAll()

				ocrResults.forEach { (image, hasText) ->
					if (hasText) {
						imagesWithText.add(image)
					} else {
						logger.debug("No text detected in image by OCR, skipping LLM")
						imagesWithoutText.add(image)
						// Cache as having no text
						webpageImageRepository.upsert(
							WebpageImage(
								imageBytesHash = image.bytesHash,
								extractedText = null
							)
						)
						cachedResults[Base64.encode(image.bytesHash)] = null
					}
				}
			}

            // Process images with text using multi-image agent
            if (imagesWithText.isNotEmpty()) {
                logger.debug("Text detected by OCR in {} images, using LLM for extraction", imagesWithText.size)
                
                val extractionOutput = multiImageTextExtractionAgent.generate(
                    MultiImageTextExtractionInput(
                        images = imagesWithText.map { image ->
                            MultiImageTextExtractionInput.ImageItem(
                                bytes = image.bytes,
                                mimeType = image.mimeType
                            )
                        }
                    )
                )

                // Cache results
                imagesWithText.forEachIndexed { index, image ->
                    val extractedText = extractionOutput.extractions[index].extractedText?.takeIf { it.isNotBlank() }
                    webpageImageRepository.upsert(
                        WebpageImage(
                            imageBytesHash = image.bytesHash,
                            extractedText = extractedText
                        )
                    )
                    cachedResults[Base64.encode(image.bytesHash)] = extractedText
                }
            }
        }

        // Return results in original order
        return images.map { image -> cachedResults[Base64.encode(image.bytesHash)] }
    }
}
