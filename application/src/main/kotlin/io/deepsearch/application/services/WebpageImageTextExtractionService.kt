package io.deepsearch.application.services

import io.deepsearch.application.services.batch.MediaData
import io.deepsearch.domain.agents.IImageClassificationAgent
import io.deepsearch.domain.agents.IImageDescriptionAgent
import io.deepsearch.domain.agents.ITableExtractionAgent
import io.deepsearch.domain.agents.ImageClassificationInput
import io.deepsearch.domain.agents.ImageDescriptionInput
import io.deepsearch.domain.agents.TableExtractionInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.entities.WebpageImage
import io.deepsearch.domain.models.valueobjects.MediaHash
import io.deepsearch.domain.models.valueobjects.OcrLanguage
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.services.IImageStorageService
import io.deepsearch.domain.services.ImageToStore
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

/**
 * Result of preparing image text extraction batch requests.
 * Contains cached results and batch requests for uncached images.
 */
data class ImageBatchPreparation(
    /** Map of image hash to cached text (null if no text extracted) */
    val cachedResults: Map<MediaHash, String?>,
    /** Pending requests for image classification (bundled with hash) */
    val pendingRequests: List<PendingMediaBatchRequest>
)

/**
 * Result of preparing table extraction batch requests for images with tables.
 */
data class ImageTableExtractionBatchPreparation(
    /** Pending requests for table extraction (bundled with hash) */
    val pendingRequests: List<PendingMediaBatchRequest>
)

interface IWebpageImageTextExtractionService {
    suspend fun extractTextFromImage(image: IBrowserPage.WebImage, sessionId: SessionId, ocrLanguage: OcrLanguage = OcrLanguage.DEFAULT): String?
    suspend fun extractTextFromImages(images: List<IBrowserPage.WebImage>, sessionId: SessionId, ocrLanguage: OcrLanguage = OcrLanguage.DEFAULT): List<String?>
    
    /**
     * Extract text from images and return results with image hashes for XML tag generation.
     */
    suspend fun extractTextFromImagesWithHashes(images: List<IBrowserPage.WebImage>, sessionId: SessionId, ocrLanguage: OcrLanguage = OcrLanguage.DEFAULT): List<ImageExtractionResult>
    
    /**
     * Prepare batch requests for image text extraction with cache check.
     * Returns cached results and batch requests for uncached images only.
     * 
     * @param images Map of media hash to image data
     * @param ocrLanguage Language for OCR text detection
     * @return Cached results and batch requests for uncached images
     */
    suspend fun prepareBatchRequests(
        images: Map<MediaHash, MediaData>,
        jobId: Long,
        ocrLanguage: OcrLanguage = OcrLanguage.DEFAULT
    ): ImageBatchPreparation
    
    /**
     * Prepare table extraction batch requests for images that contain tables.
     * Called after processing classification results.
     * 
     * @param imagesWithTables Map of media hash to image data
     * @param jobId Batch job ID
     * @return Batch requests for table extraction
     */
    suspend fun prepareTableExtractionBatchRequests(
        imagesWithTables: Map<MediaHash, MediaData>,
        jobId: Long
    ): ImageTableExtractionBatchPreparation
    
    /**
     * Process batch results and update cache.
     * 
     * @param results Map of media hash -> extracted text
     * @param imageData Map of hash -> image data for storing in cache
     */
    suspend fun processBatchResults(
        results: Map<MediaHash, String?>,
        imageData: Map<MediaHash, MediaData>
    )
}

class WebpageImageTextExtractionService(
    private val imageClassificationAgent: IImageClassificationAgent,
    private val imageDescriptionAgent: IImageDescriptionAgent,
    private val tableExtractionAgent: ITableExtractionAgent,
    private val webpageImageRepository: IWebpageImageRepository,
    private val imageStorageService: IImageStorageService,
    private val ocrService: IOcrImageTextExtractionService,
    private val tokenUsageService: ILlmTokenUsageService
) : IWebpageImageTextExtractionService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    // ========== Batch API Methods ==========

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    override suspend fun prepareBatchRequests(
        images: Map<MediaHash, MediaData>,
        jobId: Long,
        ocrLanguage: OcrLanguage
    ): ImageBatchPreparation {
        if (images.isEmpty()) {
            return ImageBatchPreparation(emptyMap(), emptyList())
        }

        // Convert hash strings to byte arrays for cache lookup
        val hashBytesMap = images.keys.associateWith { hash ->
            kotlin.io.encoding.Base64.decode(hash.value)
        }

        // Batch cache lookup
        val cachedImages = webpageImageRepository.findByHashes(hashBytesMap.values.toList())
        val cachedResults = cachedImages.associate { image ->
            MediaHash(kotlin.io.encoding.Base64.encode(image.imageBytesHash)) to image.extractedText
        }.toMutableMap()

        logger.debug("Found {} cached results for {} images", cachedResults.size, images.size)

        // Find uncached images
        val uncachedImages = images.filterKeys { hash -> !cachedResults.containsKey(hash) }

        // For batch mode, we skip OCR check and send all uncached images to LLM
        // The LLM will determine if there's text to extract
        // (OCR is used in interactive mode to reduce LLM calls, but batch has different cost structure)
        
        // Prepare classification batch requests for uncached images
        val pendingRequests = mutableListOf<PendingMediaBatchRequest>()

        uncachedImages.forEach { (hash, imageData) ->
            val request = imageClassificationAgent.prepareBatchRequest(
                requestId = "$jobId-image-${hash.value}",
                image = ImageClassificationInput.ImageItem(
                    bytes = imageData.bytes,
                    mimeType = io.deepsearch.domain.constants.ImageMimeType.fromValue(imageData.mimeType)
                )
            )
            pendingRequests.add(PendingMediaBatchRequest(hash = hash, request = request))
        }

        logger.debug("Prepared {} classification batch requests for uncached images", pendingRequests.size)

        return ImageBatchPreparation(
            cachedResults = cachedResults,
            pendingRequests = pendingRequests
        )
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    override suspend fun prepareTableExtractionBatchRequests(
        imagesWithTables: Map<MediaHash, MediaData>,
        jobId: Long
    ): ImageTableExtractionBatchPreparation {
        if (imagesWithTables.isEmpty()) {
            return ImageTableExtractionBatchPreparation(emptyList())
        }

        val pendingRequests = mutableListOf<PendingMediaBatchRequest>()

        imagesWithTables.forEach { (hash, imageData) ->
            val request = tableExtractionAgent.prepareBatchRequest(
                requestId = "$jobId-table-extract-${hash.value}",
                image = TableExtractionInput.ImageItem(
                    bytes = imageData.bytes,
                    mimeType = io.deepsearch.domain.constants.ImageMimeType.fromValue(imageData.mimeType)
                )
            )
            pendingRequests.add(PendingMediaBatchRequest(hash = hash, request = request))
        }

        logger.debug("Prepared {} table extraction batch requests", pendingRequests.size)

        return ImageTableExtractionBatchPreparation(pendingRequests = pendingRequests)
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class, kotlin.time.ExperimentalTime::class)
    override suspend fun processBatchResults(
        results: Map<MediaHash, String?>,
        imageData: Map<MediaHash, MediaData>
    ) {
        if (results.isEmpty()) return

        // Prepare images for GCS storage
        val imagesToStore = results.mapNotNull { (hash, _) ->
            val data = imageData[hash] ?: return@mapNotNull null
            val hashBytes = Base64.decode(hash.value)
            ImageToStore(
                hash = hashBytes,
                bytes = data.bytes,
                mimeType = data.mimeType
            )
        }

        // Store image bytes in GCS (returns hash -> gcsPath mapping)
        val gcsPaths = imageStorageService.storeBatch(imagesToStore)
        logger.debug("Stored {} images in GCS", gcsPaths.size)

        // Create WebpageImage entities with GCS paths for DB cache
        val imagesToCache = results.mapNotNull { (hash, text) ->
            val data = imageData[hash] ?: return@mapNotNull null
            val hashBytes = kotlin.io.encoding.Base64.decode(hash.value)
            val gcsPath = gcsPaths[hash.value] ?: return@mapNotNull null
            WebpageImage(
                imageBytesHash = hashBytes,
                gcsPath = gcsPath,
                mimeType = data.mimeType,
                extractedText = text?.takeIf { it.isNotBlank() }
            )
        }

        webpageImageRepository.batchUpsert(imagesToCache)
        logger.debug("Cached {} image text extraction results", imagesToCache.size)
    }

    // ========== Interactive Mode Methods ==========

    /**
     * Extract text from an image using Tesseract OCR + LLM.
     * First checks if the image contains any text using Tesseract.
     * If text is detected, uses LLM to extract it with proper formatting (including tables).
     * Results are cached to avoid reprocessing the same image.
     */
    override suspend fun extractTextFromImage(image: IBrowserPage.WebImage, sessionId: SessionId, ocrLanguage: OcrLanguage): String? {
        return extractTextFromImages(listOf(image), sessionId, ocrLanguage).firstOrNull()
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
    override suspend fun extractTextFromImages(images: List<IBrowserPage.WebImage>, sessionId: SessionId, ocrLanguage: OcrLanguage): List<String?> {
        return extractTextFromImagesWithHashes(images, sessionId, ocrLanguage).map { it.extractedText }
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun extractTextFromImagesWithHashes(
        images: List<IBrowserPage.WebImage>,
        sessionId: SessionId,
        ocrLanguage: OcrLanguage
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
            
            // Use OCR to partition images into those with text and those without
            val imagesWithText = mutableListOf<IBrowserPage.WebImage>()
            val imagesWithoutText = mutableListOf<IBrowserPage.WebImage>()
            
            coroutineScope {
                val ocrResults = uncachedImages.map { image ->
                    async { image to ocrService.extractText(image.bytes, image.mimeType, ocrLanguage).isNotEmpty() }
                }.awaitAll()
                
                ocrResults.forEach { (image, hasText) ->
                    if (hasText) {
                        imagesWithText.add(image)
                    } else {
                        imagesWithoutText.add(image)
                    }
                }
            }
            
            logger.debug(
                "OCR detection complete: {} images with text, {} images without text",
                imagesWithText.size,
                imagesWithoutText.size
            )
            
            // Process images WITHOUT text and WITH text IN PARALLEL
            // These two agents work on disjoint image sets, so they can run concurrently
            coroutineScope {
                // Start ImageDescriptionAgent for images without text
                val descriptionDeferred = if (imagesWithoutText.isNotEmpty()) {
                    logger.debug("Describing {} images without text using ImageDescriptionAgent", imagesWithoutText.size)
                    async {
                        imageDescriptionAgent.generate(
                            ImageDescriptionInput(
                                images = imagesWithoutText.map { image ->
                                    ImageDescriptionInput.ImageItem(
                                        bytes = image.bytes,
                                        mimeType = image.mimeType
                                    )
                                }
                            )
                        )
                    }
                } else null
                
                // Start ImageClassificationAgent for images with text (in parallel)
                val classificationDeferred = if (imagesWithText.isNotEmpty()) {
                    logger.debug("Classifying {} images with text using ImageClassificationAgent", imagesWithText.size)
                    async {
                        imageClassificationAgent.generate(
                            ImageClassificationInput(
                                images = imagesWithText.map { image ->
                                    ImageClassificationInput.ImageItem(
                                        bytes = image.bytes,
                                        mimeType = image.mimeType
                                    )
                                }
                            )
                        )
                    }
                } else null
                
                // Process description results
                descriptionDeferred?.await()?.let { descriptionOutput ->
                    // Record token usage for description
                    recordTokenUsage(sessionId, "ImageDescriptionAgent", descriptionOutput.tokenUsage)
                    
                    // Store description results - combine type, purpose, and description into searchable text
                    // Note: Avoid using square brackets here as this text will be used in markdown alt text
                    // where brackets would create invalid syntax: ![text with [brackets]](#img-N)
                    imagesWithoutText.zip(descriptionOutput.descriptions).forEach { (image, description) ->
                        cachedResults[Base64.encode(image.bytesHash)] = description.description
                    }
                    
                    // Log description results for debugging
                    val imageTypes = descriptionOutput.descriptions.groupBy { it.imageType }
                        .mapValues { it.value.size }
                    logger.debug("Description results: imageTypes={}", imageTypes)
                }
                
                // Process classification results
                classificationDeferred?.await()?.let { classificationOutput ->
                    // Record token usage for classification
                    recordTokenUsage(sessionId, "ImageClassificationAgent", classificationOutput.tokenUsage)

                    // Partition images by needsTableInterpretation flag
                    val imageClassifications = imagesWithText.zip(classificationOutput.classifications)
                    val imagesNeedingTableExtraction = imageClassifications.filter { it.second.needsTableInterpretation }
                    val imagesNotNeedingTableExtraction = imageClassifications.filter { !it.second.needsTableInterpretation }

                    // Log detailed classification results for debugging
                    val imageTypes = classificationOutput.classifications.groupBy { it.imageType }
                        .mapValues { it.value.size }
                    logger.debug(
                        "Classification results: imageTypes={}, {} need table extraction, {} don't need table extraction",
                        imageTypes,
                        imagesNeedingTableExtraction.size,
                        imagesNotNeedingTableExtraction.size
                    )

                    // For images that don't need table extraction, use classification description directly
                    imagesNotNeedingTableExtraction.forEach { (image, classification) ->
                        cachedResults[Base64.encode(image.bytesHash)] = classification.imageDescription?.takeIf { it.isNotBlank() }
                    }

                    // Stage 2: Extract tables from images that need specialized table extraction
                    if (imagesNeedingTableExtraction.isNotEmpty()) {
                        logger.debug("Extracting tables from {} images", imagesNeedingTableExtraction.size)
                        
                        val tableExtractionOutput = tableExtractionAgent.generate(
                            TableExtractionInput(
                                images = imagesNeedingTableExtraction.map { (image, _) ->
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
                        imagesNeedingTableExtraction.zip(tableExtractionOutput.extractions).forEach { (imageClassification, extraction) ->
                            val (image, _) = imageClassification
                            cachedResults[Base64.encode(image.bytesHash)] = extraction.extractedText?.takeIf { it.isNotBlank() }
                        }
                    }
                }
            }

            // Store image bytes in GCS
            val imagesToStore = uncachedImages.map { image ->
                ImageToStore(
                    hash = image.bytesHash,
                    bytes = image.bytes,
                    mimeType = image.mimeType.value
                )
            }
            val gcsPaths = imageStorageService.storeBatch(imagesToStore)
            logger.debug("Stored {} images in GCS", gcsPaths.size)

            // Cache results with GCS paths
            val imagesToCache = uncachedImages.mapNotNull { image ->
                val hashBase64 = Base64.encode(image.bytesHash)
                val gcsPath = gcsPaths[Base64.UrlSafe.encode(image.bytesHash)] ?: return@mapNotNull null
                val extractedText = cachedResults[hashBase64]
                WebpageImage(
                    imageBytesHash = image.bytesHash,
                    gcsPath = gcsPath,
                    mimeType = image.mimeType.value,
                    extractedText = extractedText
                )
            }
            webpageImageRepository.batchUpsert(imagesToCache)
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
