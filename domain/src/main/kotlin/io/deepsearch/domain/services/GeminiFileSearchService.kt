package io.deepsearch.domain.services

import com.google.genai.Client
import com.google.genai.types.CreateFileSearchStoreConfig
import com.google.genai.types.CustomMetadata
import com.google.genai.types.UploadToFileSearchStoreConfig
import com.google.genai.types.UploadToFileSearchStoreOperation
import io.deepsearch.domain.agents.FileSearchQueryInput
import io.deepsearch.domain.agents.IFileSearchQueryAgent
import io.deepsearch.domain.agents.infra.withRateLimitRetry
import io.deepsearch.domain.config.DefaultDispatcherProvider
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.FileSearchResult
import io.deepsearch.domain.models.valueobjects.FileSearchStoreInfo
import io.deepsearch.domain.models.valueobjects.GeminiFileInfo
import io.deepsearch.domain.models.valueobjects.GeminiFileMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Service for interacting with Gemini File Search stores.
 *
 * Provides RAG capabilities by uploading files to Gemini's managed file search
 * system and querying them for relevant content. Uses file metadata for:
 * - Deduplication via file hash
 * - Cache expiry via upload timestamp
 * - Citation enrichment via source URL
 */
interface IGeminiFileSearchService {

    /**
     * Find an existing file search store for a domain.
     *
     * @param domain The domain (e.g., "docs.example.com") to look up
     * @return Information about the store, or null if no store exists for this domain
     */
    suspend fun findStore(domain: String): FileSearchStoreInfo?

    /**
     * Get an existing file search store for a domain, or create a new one.
     *
     * @param domain The domain (e.g., "docs.example.com") to associate with the store
     * @return Information about the store
     */
    suspend fun getOrCreateStore(domain: String): FileSearchStoreInfo

    /**
     * Find a file in the store by its hash.
     * Uses the custom metadata stored with each file to locate it.
     *
     * @param storeName The Gemini store resource name
     * @param fileHash SHA-256 hash of the file content
     * @return File info if found, null otherwise
     */
    suspend fun findFileByHash(storeName: String, fileHash: String): GeminiFileInfo?

    /**
     * Upload a file to the file search store.
     * The file is automatically chunked, embedded, and indexed by Gemini.
     *
     * @param storeName The Gemini store resource name
     * @param fileBytes The file content
     * @param mimeType MIME type of the file
     * @param sourceUrl Original URL where the file was found
     * @param fileHash SHA-256 hash of the file content
     * @return Information about the uploaded file
     */
    suspend fun uploadFile(
        storeName: String,
        fileBytes: ByteArray,
        mimeType: String,
        sourceUrl: String,
        fileHash: String
    ): GeminiFileInfo

    /**
     * Query a file search store for relevant content.
     *
     * @param storeName The Gemini store resource name
     * @param query The search query
     * @param maxAgeMs Optional maximum age in milliseconds; files uploaded before
     *                 this threshold are filtered out server-side via metadataFilter
     * @return Search results with content chunks and citations
     */
    suspend fun queryStore(
        storeName: String,
        query: String,
        maxAgeMs: Long? = null
    ): FileSearchResult

    /**
     * Delete a file from the store.
     * Used when re-indexing expired files.
     *
     * @param storeName The Gemini store resource name
     * @param fileName The Gemini file resource name to delete
     */
    suspend fun deleteFile(storeName: String, fileName: String)

    /**
     * List all files in a store.
     * Useful for cache management and debugging.
     *
     * @param storeName The Gemini store resource name
     * @return List of files in the store
     */
    suspend fun listFiles(storeName: String): List<GeminiFileInfo>
}

/**
 * Implementation of Gemini File Search service using the Google GenAI SDK.
 *
 * Uses SDK methods for all operations including store management, file operations,
 * and querying via the FileSearch tool.
 *
 * Uses a naming convention for store displayName to associate stores with domains:
 * - Format: "deepsearch-{domain}" (e.g., "deepsearch-docs.example.com")
 * - Stores are looked up via SDK list() and matched by displayName
 * - This eliminates the need for local state, making the Gemini API the single source of truth
 *
 * @see https://ai.google.dev/api/file-search/file-search-stores
 */
@OptIn(ExperimentalTime::class)
class GeminiFileSearchService(
    private val client: Client,
    private val fileSearchQueryAgent: IFileSearchQueryAgent,
    private val dispatcherProvider: IDispatcherProvider
) : IGeminiFileSearchService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val MAX_POLL_ATTEMPTS = 60
        private const val POLL_INTERVAL_MS = 5000L
        private const val STORE_DISPLAY_NAME_PREFIX = "deepsearch-"
    }

    private fun domainToDisplayName(domain: String): String = "$STORE_DISPLAY_NAME_PREFIX$domain"

    // ==================== Store Management ====================

    override suspend fun findStore(domain: String): FileSearchStoreInfo? = withContext(dispatcherProvider.io) {
        val targetDisplayName = domainToDisplayName(domain)
        logger.debug("Looking for file search store with displayName: {}", targetDisplayName)

        val pager = withRateLimitRetry("${this@GeminiFileSearchService::class.simpleName}.findStore") {
            client.fileSearchStores.list(null)
        }

        for (store in pager) {
            val storeDisplayName = store.displayName().orElse(null) ?: continue
            if (storeDisplayName == targetDisplayName) {
                val storeName = store.name().orElse(null) ?: continue
                logger.debug("Found existing file search store for domain {}: {}", domain, storeName)
                return@withContext FileSearchStoreInfo(
                    name = storeName,
                    displayName = storeDisplayName,
                    domain = domain
                )
            }
        }

        logger.debug("No file search store found for domain: {}", domain)
        null
    }

    override suspend fun getOrCreateStore(domain: String): FileSearchStoreInfo = withContext(dispatcherProvider.io) {
        // Check Gemini API for existing store with matching displayName
        val existing = findStore(domain)
        if (existing != null) {
            return@withContext existing
        }

        // Create new store via SDK
        logger.info("Creating new file search store for domain: {}", domain)
        val displayName = domainToDisplayName(domain)

        val store = withRateLimitRetry("${this@GeminiFileSearchService::class.simpleName}.createStore") {
            client.fileSearchStores.create(
                CreateFileSearchStoreConfig.builder()
                    .displayName(displayName)
                    .build()
            )
        }

        val storeName = store.name().orElseThrow {
            IllegalStateException("Created store does not have a name")
        }

        logger.info("Created file search store: {} for domain: {}", storeName, domain)

        FileSearchStoreInfo(
            name = storeName,
            displayName = displayName,
            domain = domain
        )
    }

    // ==================== Find File ====================

    override suspend fun findFileByHash(storeName: String, fileHash: String): GeminiFileInfo? {
        // Always query Gemini API via SDK (stateless approach)
        val documents = listFilesFromSdk(storeName)
        return documents.find { it.fileHash == fileHash }
    }

    // ==================== Upload ====================

    override suspend fun uploadFile(
        storeName: String,
        fileBytes: ByteArray,
        mimeType: String,
        sourceUrl: String,
        fileHash: String
    ): GeminiFileInfo = withContext(dispatcherProvider.io) {
        val now = Clock.System.now()
        val displayName = extractDisplayName(sourceUrl)

        logger.debug(
            "Uploading file to store {}: {} ({} bytes, {})",
            storeName, displayName, fileBytes.size, mimeType
        )

        // Build custom metadata
        val customMetadata = listOf(
            CustomMetadata.builder()
                .key(GeminiFileMetadata.KEY_FILE_HASH)
                .stringValue(fileHash)
                .build(),
            CustomMetadata.builder()
                .key(GeminiFileMetadata.KEY_UPLOADED_AT)
                .stringValue(now.toEpochMilliseconds().toString())
                .build(),
            CustomMetadata.builder()
                .key(GeminiFileMetadata.KEY_SOURCE_URL)
                .stringValue(sourceUrl)
                .build()
        )

        // Upload via SDK - returns UploadToFileSearchStoreOperation
        val operation = withRateLimitRetry("${this@GeminiFileSearchService::class.simpleName}.uploadFile") {
            client.fileSearchStores.uploadToFileSearchStore(
                storeName,
                fileBytes,
                UploadToFileSearchStoreConfig.builder()
                    .displayName(displayName)
                    .mimeType(mimeType)
                    .customMetadata(customMetadata)
                    .build()
            )
        }

        // Poll for completion using typed operation object
        val documentName = pollUploadOperation(operation)

        logger.info("File uploaded: {} -> {}", documentName, storeName)

        GeminiFileInfo(
            name = documentName,
            displayName = displayName,
            mimeType = mimeType,
            fileHash = fileHash,
            sourceUrl = sourceUrl,
            uploadedAt = now
        )
    }

    // ==================== Querying ====================

    override suspend fun queryStore(
        storeName: String,
        query: String,
        maxAgeMs: Long?
    ): FileSearchResult {
        logger.debug("Querying file search store {}: '{}' (maxAgeMs: {})", storeName, query, maxAgeMs)

        val result = fileSearchQueryAgent.generate(
            FileSearchQueryInput(
                storeName = storeName,
                query = query,
                maxAgeMs = maxAgeMs
            )
        )

        logger.debug("File search returned {} chunks", result.chunks.size)

        return FileSearchResult(
            chunks = result.chunks,
            tokenUsage = result.tokenUsage
        )
    }

    // ==================== Delete ====================

    override suspend fun deleteFile(storeName: String, fileName: String) = withContext(dispatcherProvider.io) {
        logger.info("Deleting file {} from store {}", fileName, storeName)

        try {
            withRateLimitRetry("${this@GeminiFileSearchService::class.simpleName}.deleteFile") {
                client.fileSearchStores.documents.delete(fileName, null)
            }
            logger.debug("File deleted: {}", fileName)
        } catch (e: Exception) {
            logger.warn("Failed to delete file: {}", e.message)
        }
    }

    // ==================== List Files ====================

    override suspend fun listFiles(storeName: String): List<GeminiFileInfo> {
        return listFilesFromSdk(storeName)
    }

    private suspend fun listFilesFromSdk(storeName: String): List<GeminiFileInfo> = withContext(dispatcherProvider.io) {
        logger.debug("Listing files in store: {}", storeName)

        val pager = withRateLimitRetry("${this@GeminiFileSearchService::class.simpleName}.listFiles") {
            client.fileSearchStores.documents.list(storeName, null)
        }

        val results = mutableListOf<GeminiFileInfo>()
        for (doc in pager) {
            val name = doc.name().orElse(null) ?: continue
            val docDisplayName = doc.displayName().orElse("Unknown")
            val docMimeType = doc.mimeType().orElse("application/octet-stream")

            // Extract custom metadata
            val metadataList = doc.customMetadata().orElse(emptyList())
            val metadataMap = metadataList.associate { meta ->
                val key = meta.key().orElse("")
                val value = meta.stringValue().orElse(meta.numericValue().map { it.toString() }.orElse(""))
                key to value
            }

            val metadata = GeminiFileMetadata.fromMap(metadataMap) ?: continue

            results.add(
                GeminiFileInfo(
                    name = name,
                    displayName = docDisplayName,
                    mimeType = docMimeType,
                    fileHash = metadata.fileHash,
                    sourceUrl = metadata.sourceUrl,
                    uploadedAt = Instant.fromEpochMilliseconds(metadata.uploadedAtEpochMs)
                )
            )
        }

        results
    }

    // ==================== Helper Methods ====================

    /**
     * Poll for upload operation completion using the typed UploadToFileSearchStoreOperation.
     *
     * The SDK's operations.get() method requires a typed Operation object, not a string.
     * When the operation is done, we extract the document name from the typed response.
     */
    private suspend fun pollUploadOperation(initialOperation: UploadToFileSearchStoreOperation): String =
        withContext(dispatcherProvider.io) {
            val operationName = initialOperation.name().orElseThrow {
                RuntimeException("Operation does not have a name")
            }

            var currentOperation = initialOperation

            repeat(MAX_POLL_ATTEMPTS) { attempt ->
                val isDone = currentOperation.done().orElse(false)
                if (isDone) {
                    // Check for error
                    currentOperation.error().ifPresent { error ->
                        throw RuntimeException("Upload failed: $error")
                    }

                    // Extract document name from the typed response
                    val response = currentOperation.response().orElse(null)
                    if (response != null) {
                        val documentName = response.documentName().orElse(null)
                        if (documentName != null) {
                            return@withContext documentName
                        }
                    }

                    // Fallback: derive document name from operation name
                    return@withContext operationName.replace("/operations/", "/documents/")
                }

                delay(POLL_INTERVAL_MS)
                logger.debug("Polling upload operation {}: attempt {}", operationName, attempt + 1)

                // Refresh the operation status using typed operation object
                currentOperation = withRateLimitRetry("${this@GeminiFileSearchService::class.simpleName}.pollOperation") {
                    client.operations.get(currentOperation, null)
                }
            }

            throw RuntimeException("Upload operation timed out: $operationName")
        }

    private fun extractDisplayName(url: String): String {
        return try {
            java.net.URI(url).path.substringAfterLast('/').ifBlank { "document" }
        } catch (e: Exception) {
            "document-${System.currentTimeMillis()}"
        }
    }
}
