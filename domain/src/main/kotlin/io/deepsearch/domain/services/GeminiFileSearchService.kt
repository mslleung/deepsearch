package io.deepsearch.domain.services

import com.google.genai.Client
import com.google.genai.types.CreateFileSearchStoreConfig
import com.google.genai.types.CustomMetadata
import com.google.genai.types.UploadToFileSearchStoreConfig
import io.deepsearch.domain.agents.FileSearchQueryInput
import io.deepsearch.domain.agents.IFileSearchQueryAgent
import io.deepsearch.domain.models.valueobjects.FileSearchResult
import io.deepsearch.domain.models.valueobjects.FileSearchStoreInfo
import io.deepsearch.domain.models.valueobjects.GeminiFileInfo
import io.deepsearch.domain.models.valueobjects.GeminiFileMetadata
import io.deepsearch.domain.repositories.IFileSearchStoreRepository
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
     * @param maxAgeMs Optional maximum age in milliseconds; results from files
     *                 uploaded before this threshold are filtered out client-side
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
 * @see https://ai.google.dev/api/file-search/file-search-stores
 */
@OptIn(ExperimentalTime::class)
class GeminiFileSearchService(
    private val client: Client,
    private val fileSearchStoreRepository: IFileSearchStoreRepository,
    private val fileSearchQueryAgent: IFileSearchQueryAgent
) : IGeminiFileSearchService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val MAX_POLL_ATTEMPTS = 60
        private const val POLL_INTERVAL_MS = 5000L
    }

    // ==================== Store Management ====================

    override suspend fun findStore(domain: String): FileSearchStoreInfo? {
        val existing = fileSearchStoreRepository.findByDomain(domain) ?: return null
        logger.debug("Found existing file search store for domain {}: {}", domain, existing.geminiStoreName)
        return FileSearchStoreInfo(
            name = existing.geminiStoreName,
            displayName = "DeepSearch-$domain",
            domain = domain
        )
    }

    override suspend fun getOrCreateStore(domain: String): FileSearchStoreInfo {
        // Check local repository first
        val existing = fileSearchStoreRepository.findByDomain(domain)
        if (existing != null) {
            logger.debug("Found existing file search store for domain {}: {}", domain, existing.geminiStoreName)
            return FileSearchStoreInfo(
                name = existing.geminiStoreName,
                displayName = "DeepSearch-$domain",
                domain = domain
            )
        }

        // Create new store via SDK
        logger.info("Creating new file search store for domain: {}", domain)
        val displayName = "DeepSearch-$domain"

        val store = withContext(Dispatchers.IO) {
            client.fileSearchStores.create(
                CreateFileSearchStoreConfig.builder()
                    .displayName(displayName)
                    .build()
            )
        }

        val storeName = store.name().orElseThrow {
            IllegalStateException("Created store does not have a name")
        }

        logger.info("Created file search store: {}", storeName)

        // Save to local repository
        fileSearchStoreRepository.create(
            io.deepsearch.domain.models.entities.FileSearchStore(
                domain = domain,
                geminiStoreName = storeName
            )
        )

        return FileSearchStoreInfo(
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
    ): GeminiFileInfo {
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

        // Upload via SDK
        val operation = withContext(Dispatchers.IO) {
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

        // Poll for completion using SDK
        val documentName = pollUploadOperation(operation.name().orElse(""))

        logger.info("File uploaded: {} -> {}", documentName, storeName)

        return GeminiFileInfo(
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
        logger.debug("Querying file search store {}: '{}'", storeName, query)

        val result = fileSearchQueryAgent.generate(
            FileSearchQueryInput(
                storeName = storeName,
                query = query
            )
        )

        logger.debug("File search returned {} chunks", result.chunks.size)

        return FileSearchResult(
            chunks = result.chunks,
            tokenUsage = result.tokenUsage
        )
    }

    // ==================== Delete ====================

    override suspend fun deleteFile(storeName: String, fileName: String) {
        logger.info("Deleting file {} from store {}", fileName, storeName)

        try {
            withContext(Dispatchers.IO) {
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

    private suspend fun listFilesFromSdk(storeName: String): List<GeminiFileInfo> {
        logger.debug("Listing files in store: {}", storeName)

        val pager = withContext(Dispatchers.IO) {
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

        return results
    }

    // ==================== Helper Methods ====================

    private suspend fun pollUploadOperation(operationName: String): String {
        if (operationName.isBlank()) {
            throw RuntimeException("Operation name is blank")
        }

        repeat(MAX_POLL_ATTEMPTS) { attempt ->
            val operation = withContext(Dispatchers.IO) {
                client.operations.get(operationName, null)
            }

            val isDone = operation.done().orElse(false)
            if (isDone) {
                // Check for error
                operation.error().ifPresent { error ->
                    val message = error.message().orElse("Unknown error")
                    throw RuntimeException("Upload failed: $message")
                }

                // Extract document name from response metadata
                val response = operation.response().orElse(null)
                if (response != null) {
                    val nameValue = response["name"]
                    if (nameValue is String && nameValue.contains("documents")) {
                        return nameValue
                    }
                }

                // Fallback: derive document name from operation name
                return operationName.replace("/operations/", "/documents/")
            }

            delay(POLL_INTERVAL_MS)
            logger.debug("Polling upload operation {}: attempt {}", operationName, attempt + 1)
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
