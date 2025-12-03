package io.deepsearch.domain.services

import com.google.genai.Client
import com.google.genai.types.CreateFileSearchStoreConfig
import com.google.genai.types.CustomMetadata
import com.google.genai.types.UploadToFileSearchStoreConfig
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.models.valueobjects.FileSearchChunk
import io.deepsearch.domain.models.valueobjects.FileSearchResult
import io.deepsearch.domain.models.valueobjects.FileSearchStoreInfo
import io.deepsearch.domain.models.valueobjects.GeminiFileInfo
import io.deepsearch.domain.models.valueobjects.GeminiFileMetadata
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.repositories.IFileSearchStoreRepository
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Implementation of Gemini File Search service using the Google GenAI SDK.
 * 
 * Uses SDK methods for store management and file operations.
 * Falls back to REST API for generateContent with fileSearch tool (not yet in SDK).
 * 
 * @see https://ai.google.dev/api/file-search/file-search-stores
 */
@OptIn(ExperimentalTime::class)
class GeminiFileSearchServiceImpl(
    private val client: Client,
    private val fileSearchStoreRepository: IFileSearchStoreRepository
) : IGeminiFileSearchService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val apiKey: String = System.getenv("GOOGLE_API_KEY")
        ?: throw IllegalStateException("GOOGLE_API_KEY environment variable not set")

    // For generateContent with fileSearch tool (not yet in SDK)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
    }
    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta"

    // Local cache of uploaded files per store (keyed by fileHash)
    private val uploadedFilesCache = ConcurrentHashMap<String, ConcurrentHashMap<String, GeminiFileInfo>>()

    companion object {
        private const val MAX_POLL_ATTEMPTS = 60
        private const val POLL_INTERVAL_MS = 5000L
    }

    // ==================== Store Management (SDK) ====================

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

        uploadedFilesCache[storeName] = ConcurrentHashMap()

        return FileSearchStoreInfo(
            name = storeName,
            displayName = displayName,
            domain = domain
        )
    }

    override suspend fun findFileByHash(storeName: String, fileHash: String): GeminiFileInfo? {
        // Check local cache first
        uploadedFilesCache[storeName]?.get(fileHash)?.let { return it }

        // Query Gemini API via SDK
        val documents = listFilesFromSdk(storeName)
        return documents.find { it.fileHash == fileHash }?.also { fileInfo ->
            uploadedFilesCache.computeIfAbsent(storeName) { ConcurrentHashMap() }[fileHash] = fileInfo
        }
    }

    override suspend fun uploadFile(
        storeName: String,
        fileBytes: ByteArray,
        mimeType: String,
        sourceUrl: String,
        fileHash: String
    ): GeminiFileInfo {
        val now = Clock.System.now()
        val displayName = extractDisplayName(sourceUrl)

        logger.debug("Uploading file to store {}: {} ({} bytes, {})",
            storeName, displayName, fileBytes.size, mimeType)

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

        // Poll for completion
        val documentName = pollUploadOperation(operation.name().orElse(""))

        logger.info("File uploaded: {} -> {}", documentName, storeName)

        val fileInfo = GeminiFileInfo(
            name = documentName,
            displayName = displayName,
            mimeType = mimeType,
            fileHash = fileHash,
            sourceUrl = sourceUrl,
            uploadedAt = now
        )

        uploadedFilesCache.computeIfAbsent(storeName) { ConcurrentHashMap() }[fileHash] = fileInfo
        return fileInfo
    }

    // ==================== Querying (REST API - fileSearch tool not in SDK yet) ====================

    override suspend fun queryStore(
        storeName: String,
        query: String,
        maxAgeMs: Long?
    ): FileSearchResult {
        logger.debug("Querying file search store {}: '{}'", storeName, query)

        val modelId = ModelIds.GEMINI_2_5_FLASH.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        try {
            // Use REST API for generateContent with fileSearch tool
            val escapedQuery = query.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
            val requestBody = """
                {
                    "contents": [{"parts": [{"text": "$escapedQuery"}]}],
                    "tools": [{"fileSearch": {"fileSearchStores": ["$storeName"]}}],
                    "generationConfig": {"temperature": 0.1},
                    "systemInstruction": {"parts": [{"text": "Answer using only information from the file search results. Include relevant quotes."}]}
                }
            """.trimIndent()

            val response = httpClient.post("$baseUrl/models/$modelId:generateContent") {
                parameter("key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }

            if (!response.status.isSuccess()) {
                logger.warn("File search query failed: {}", response.status)
                return FileSearchResult(chunks = emptyList(), tokenUsage = tokenUsage)
            }

            val responseText = response.bodyAsText()
            val chunks = parseGenerateContentResponse(responseText)
            tokenUsage = extractTokenUsage(responseText, modelId)

            logger.debug("File search returned {} chunks", chunks.size)
            return FileSearchResult(chunks = chunks, tokenUsage = tokenUsage)

        } catch (e: Exception) {
            logger.error("File search query failed: {}", e.message, e)
            return FileSearchResult(chunks = emptyList(), tokenUsage = tokenUsage)
        }
    }

    // ==================== Delete (SDK) ====================

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

        // Remove from cache
        uploadedFilesCache[storeName]?.let { cache ->
            cache.entries.find { it.value.name == fileName }?.key?.let { cache.remove(it) }
        }
    }

    // ==================== List Files (SDK) ====================

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

            results.add(GeminiFileInfo(
                name = name,
                displayName = docDisplayName,
                mimeType = docMimeType,
                fileHash = metadata.fileHash,
                sourceUrl = metadata.sourceUrl,
                uploadedAt = Instant.fromEpochMilliseconds(metadata.uploadedAtEpochMs)
            ))
        }
        
        return results
    }

    // ==================== Helper Methods ====================

    private suspend fun pollUploadOperation(operationName: String): String {
        if (operationName.isBlank()) {
            throw RuntimeException("Operation name is blank")
        }

        repeat(MAX_POLL_ATTEMPTS) { attempt ->
            // Use REST API for polling since SDK operations.get has complex generics
            val response = httpClient.get("$baseUrl/$operationName") {
                parameter("key", apiKey)
            }

            if (!response.status.isSuccess()) {
                throw RuntimeException("Failed to poll operation: ${response.status}")
            }

            val responseText = response.bodyAsText()
            
            // Check if done
            val donePattern = """"done"\s*:\s*true""".toRegex()
            if (donePattern.containsMatchIn(responseText)) {
                // Check for error
                val errorPattern = """"error"\s*:\s*\{[^}]*"message"\s*:\s*"([^"]+)"""".toRegex()
                errorPattern.find(responseText)?.let { match ->
                    throw RuntimeException("Upload failed: ${match.groupValues[1]}")
                }

                // Extract document name from response
                val namePattern = """"name"\s*:\s*"([^"]+documents[^"]+)"""".toRegex()
                namePattern.find(responseText)?.let { match ->
                    return match.groupValues[1]
                }

                // Fallback: operation completed but no name in response
                return operationName.replace("/operations/", "/documents/")
            }

            delay(POLL_INTERVAL_MS)
            logger.debug("Polling upload operation {}: attempt {}", operationName, attempt + 1)
        }

        throw RuntimeException("Upload operation timed out: $operationName")
    }

    private fun parseGenerateContentResponse(responseJson: String): List<FileSearchChunk> {
        val chunks = mutableListOf<FileSearchChunk>()
        try {
            val textPattern = """"text"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex()
            textPattern.findAll(responseJson).forEach { match ->
                val text = match.groupValues[1]
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")

                if (text.isNotBlank() && text.length > 20) {
                    chunks.add(FileSearchChunk(
                        content = text,
                        sourceUrl = "",
                        fileName = "File Search Result",
                        relevanceScore = null
                    ))
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse response: {}", e.message)
        }
        return chunks.take(5)
    }

    private fun extractTokenUsage(responseJson: String, modelId: String): TokenUsageMetrics {
        return try {
            val promptPattern = """"promptTokenCount"\s*:\s*(\d+)""".toRegex()
            val outputPattern = """"candidatesTokenCount"\s*:\s*(\d+)""".toRegex()
            val totalPattern = """"totalTokenCount"\s*:\s*(\d+)""".toRegex()

            val prompt = promptPattern.find(responseJson)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val output = outputPattern.find(responseJson)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val total = totalPattern.find(responseJson)?.groupValues?.get(1)?.toIntOrNull() ?: (prompt + output)

            TokenUsageMetrics(modelId, prompt, output, total)
        } catch (e: Exception) {
            TokenUsageMetrics.empty(modelId)
        }
    }

    private fun extractDisplayName(url: String): String {
        return try {
            java.net.URI(url).path.substringAfterLast('/').ifBlank { "document" }
        } catch (e: Exception) {
            "document-${System.currentTimeMillis()}"
        }
    }
}
