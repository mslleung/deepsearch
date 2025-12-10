package io.deepsearch.application.services

import io.deepsearch.domain.agents.IImageClassificationAgent
import io.deepsearch.domain.agents.ITableExtractionAgent
import io.deepsearch.domain.agents.ImageClassificationInput
import io.deepsearch.domain.agents.TableExtractionInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.entities.WebpageImage
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.services.IOcrImageTextExtractionService
import io.deepsearch.domain.repositories.IWebpageImageRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.io.encoding.Base64
import kotlin.time.ExperimentalTime

/**
 * Result of image text extraction, containing both the extracted text and the image hash.
 * The hash can be used to generate unique image IDs for XML tags.
 */
data class ImageExtractionResult(
    val extractedText: String?,
    val imageBytesHash: ByteArray
) {
    /**
     * Returns a base64-encoded hash suitable for use as an image ID.
     * Format: "img-{base64Hash}" with URL-safe encoding (+ -> -, / -> _)
     */
    fun toImageId(): String {
        val base64Hash = Base64.encode(imageBytesHash)
        val urlSafeHash = base64Hash.replace("+", "-").replace("/", "_").trimEnd('=')
        return "img-$urlSafeHash"
    }
}

interface IWebpageImageTextExtractionService {
    suspend fun extractTextFromImage(image: IBrowserPage.WebImage, sessionId: SessionId): String?
    suspend fun extractTextFromImages(images: List<IBrowserPage.WebImage>, sessionId: SessionId): List<String?>
    
    /**
     * Extract text from images and return results with image hashes for XML tag generation.
     */
    suspend fun extractTextFromImagesWithHashes(images: List<IBrowserPage.WebImage>, sessionId: SessionId): List<ImageExtractionResult>
}

class WebpageImageTextExtractionService(
    private val imageClassificationAgent: IImageClassificationAgent,
    private val tableExtractionAgent: ITableExtractionAgent,
    private val webpageImageRepository: IWebpageImageRepository,
    private val ocrService: IOcrImageTextExtractionService,
    private val tokenUsageService: ILlmTokenUsageService
) : IWebpageImageTextExtractionService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Extract text from an image using Tesseract OCR + LLM.
     * First checks if the image contains any text using Tesseract.
     * If text is detected, uses LLM to extract it with proper formatting (including tables).
     * Results are cached to avoid reprocessing the same image.
     */
    override suspend fun extractTextFromImage(image: IBrowserPage.WebImage, sessionId: SessionId): String? {
        return extractTextFromImages(listOf(image), sessionId).firstOrNull()
    }

    /**
     * Extract text from multiple images in batch using Tesseract OCR + LLM.
     * Efficiently processes multiple images by:
     * - Checking cache first for all images
     * - Batching uncached images for LLM processing
     * - Using a two-stage approach: classification first, then table extraction if needed
     * Results are cached to avoid reprocessing the same images.
     */
    @OptIn(ExperimentalTime::class)
    override suspend fun extractTextFromImages(images: List<IBrowserPage.WebImage>, sessionId: SessionId): List<String?> {
        return extractTextFromImagesWithHashes(images, sessionId).map { it.extractedText }
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun extractTextFromImagesWithHashes(
        images: List<IBrowserPage.WebImage>,
        sessionId: SessionId
    ): List<ImageExtractionResult> {
        if (images.isEmpty()) {
            return emptyList()
        }

        // Batch cache lookup (single DB query instead of N queries)
        val imageHashes = images.map { it.bytesHash }
        val cachedImages = webpageImageRepository.findByHashes(imageHashes)
        val cachedResults = cachedImages.associate { image ->
            Base64.encode(image.imageBytesHash) to image.extractedText?.takeIf { it.isNotBlank() }
        }.toMutableMap()
        
        logger.debug("Found {} cached results for {} images", cachedResults.size, images.size)

        // Find uncached images by checking which hashes are not in cached results
        val uncachedImages = images.filter { image ->
            !cachedResults.containsKey(Base64.encode(image.bytesHash))
        }

        // Process uncached images
        if (uncachedImages.isNotEmpty()) {
            logger.debug("Processing {} uncached images", uncachedImages.size)
            
            // Filter images by OCR text detection
            val imagesWithText = mutableListOf<IBrowserPage.WebImage>()
            
            coroutineScope {
                val ocrResults = uncachedImages.map { image ->
                    async { image to !ocrService.extractText(image.bytes, image.mimeType).isEmpty() }
                }.awaitAll()

                val imagesToCacheWithoutText = mutableListOf<WebpageImage>()
                
                ocrResults.forEach { (image, hasText) ->
                    if (hasText) {
                        imagesWithText.add(image)
                    } else {
                        logger.debug("No text detected in image by OCR, skipping LLM")
                        // Cache as having no text, but still store raw bytes
                        imagesToCacheWithoutText.add(
                            WebpageImage(
                                imageBytesHash = image.bytesHash,
                                imageBytes = image.bytes,
                                mimeType = image.mimeType.value,
                                extractedText = null
                            )
                        )
                        cachedResults[Base64.encode(image.bytesHash)] = null
                    }
                }
                
                if (imagesToCacheWithoutText.isNotEmpty()) {
                    webpageImageRepository.batchUpsert(imagesToCacheWithoutText)
                }
            }

            // Process images with text using two-stage approach
            if (imagesWithText.isNotEmpty()) {
                logger.debug("Text detected by OCR in {} images, using LLM for classification", imagesWithText.size)
                
                // Stage 1: Classify all images and extract initial text
                val classificationOutput = imageClassificationAgent.generate(
                    ImageClassificationInput(
                        images = imagesWithText.map { image ->
                            ImageClassificationInput.ImageItem(
                                bytes = image.bytes,
                                mimeType = image.mimeType
                            )
                        }
                    )
                )

                // Record token usage for classification
                recordTokenUsage(sessionId, "ImageClassificationAgent", classificationOutput.tokenUsage)

                // Partition images by containsTable flag
                val imageClassifications = imagesWithText.zip(classificationOutput.classifications)
                val imagesWithTables = imageClassifications.filter { it.second.containsTable }
                val imagesWithoutTables = imageClassifications.filter { !it.second.containsTable }

                // Log detailed classification results for debugging
                val illustrativeCount = classificationOutput.classifications.count { 
                    it.imageType == io.deepsearch.domain.agents.ImageClassificationOutput.ImageType.ILLUSTRATIVE 
                }
                val informationalCount = classificationOutput.classifications.count { 
                    it.imageType == io.deepsearch.domain.agents.ImageClassificationOutput.ImageType.INFORMATIONAL 
                }
                logger.debug(
                    "Classification results: {} illustrative, {} informational, {} with tables, {} without tables",
                    illustrativeCount,
                    informationalCount,
                    imagesWithTables.size,
                    imagesWithoutTables.size
                )

                // For images without tables, use classification text directly
                imagesWithoutTables.forEach { (image, classification) ->
                    cachedResults[Base64.encode(image.bytesHash)] = classification.text?.takeIf { it.isNotBlank() }
                }

                // Stage 2: Extract tables from images that contain them
                if (imagesWithTables.isNotEmpty()) {
                    logger.debug("Extracting tables from {} images", imagesWithTables.size)
                    
                    val tableExtractionOutput = tableExtractionAgent.generate(
                        TableExtractionInput(
                            images = imagesWithTables.map { (image, _) ->
                                TableExtractionInput.ImageItem(
                                    bytes = image.bytes,
                                    mimeType = image.mimeType
                                )
                            }
                        )
                    )

                    // Record token usage for table extraction
                    recordTokenUsage(sessionId, "TableExtractionAgent", tableExtractionOutput.tokenUsage)

                    // Store table extraction results
                    imagesWithTables.zip(tableExtractionOutput.extractions).forEach { (imageClassification, extraction) ->
                        val (image, _) = imageClassification
                        cachedResults[Base64.encode(image.bytesHash)] = extraction.extractedText?.takeIf { it.isNotBlank() }
                    }
                }

                // Cache all results with raw bytes
                val imagesToCache = imagesWithText.map { image ->
                    val extractedText = cachedResults[Base64.encode(image.bytesHash)]
                    WebpageImage(
                        imageBytesHash = image.bytesHash,
                        imageBytes = image.bytes,
                        mimeType = image.mimeType.value,
                        extractedText = extractedText
                    )
                }
                webpageImageRepository.batchUpsert(imagesToCache)
            }
        }

        // Return results in original order with hashes
        return images.map { image -> 
            ImageExtractionResult(
                extractedText = cachedResults[Base64.encode(image.bytesHash)],
                imageBytesHash = image.bytesHash
            )
        }
    }

    private suspend fun recordTokenUsage(sessionId: SessionId, agentName: String, tokenUsage: TokenUsageMetrics) {
        tokenUsageService.recordTokenUsage(
            sessionId = sessionId,
            agentName = agentName,
            modelName = tokenUsage.modelName,
            promptTokens = tokenUsage.promptTokens,
            outputTokens = tokenUsage.outputTokens,
            totalTokens = tokenUsage.totalTokens
        )
    }
}
