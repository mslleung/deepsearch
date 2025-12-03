package io.deepsearch.application.services

import io.deepsearch.domain.models.valueobjects.FileSearchChunk
import io.deepsearch.domain.models.valueobjects.FileSearchResult
import io.deepsearch.domain.models.valueobjects.GeminiFileInfo
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.services.IGeminiFileSearchService
import io.deepsearch.domain.services.INormalizeUrlService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URI
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Result of file ingestion and query.
 */
@OptIn(ExperimentalTime::class)
data class FileIngestResult(
    val fileInfo: GeminiFileInfo,
    val wasUploaded: Boolean,        // true if file was newly uploaded, false if existing
    val searchResult: FileSearchResult,
    val markdown: String,            // Combined markdown from search results
    val citations: List<FileCitation>
)

/**
 * A citation from a file search result.
 */
data class FileCitation(
    val fileName: String,
    val sourceUrl: String,
    val content: String
)

/**
 * Service interface for file ingestion into Gemini File Search.
 */
interface IFileIngestionService {
    /**
     * Ingest a file and immediately query for relevant content.
     * 
     * Handles deduplication via file hash:
     * - If file with same hash exists and is not expired, skips upload
     * - If file is new or expired, uploads with metadata
     * - Always queries the file search store for relevant content
     * 
     * @param url Original URL where the file was found
     * @param fileBytes The file content
     * @param mimeType MIME type of the file
     * @param query Search query for retrieving relevant content
     * @param maxCacheAge Maximum age in milliseconds for cached files (null = no expiry)
     * @param sessionId Session ID for token tracking
     * @return FileIngestResult containing file info, search results, and markdown
     */
    suspend fun ingestAndQuery(
        url: String,
        fileBytes: ByteArray,
        mimeType: String,
        query: String,
        maxCacheAge: Long?,
        sessionId: SessionId
    ): FileIngestResult
}

/**
 * Service for ingesting files into Gemini File Search with deduplication.
 */
@OptIn(ExperimentalTime::class, ExperimentalEncodingApi::class)
class FileIngestionService(
    private val geminiFileSearchService: IGeminiFileSearchService,
    private val normalizeUrlService: INormalizeUrlService,
    private val tokenUsageService: ILlmTokenUsageService
) : IFileIngestionService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun ingestAndQuery(
        url: String,
        fileBytes: ByteArray,
        mimeType: String,
        query: String,
        maxCacheAge: Long?,
        sessionId: SessionId
    ): FileIngestResult {
        logger.debug("Ingesting file from {}: {} bytes, type: {}", url, fileBytes.size, mimeType)

        // Calculate file hash
        val fileHash = calculateHash(fileBytes)
        logger.debug("File hash: {}", fileHash)

        // Extract domain and get/create file search store
        val domain = extractDomain(url)
        val storeInfo = geminiFileSearchService.getOrCreateStore(domain)
        logger.debug("Using file search store: {} for domain: {}", storeInfo.name, domain)

        // Check for existing file with matching hash
        val existingFile = geminiFileSearchService.findFileByHash(storeInfo.name, fileHash)
        
        val (fileInfo, wasUploaded) = if (existingFile != null) {
            val fileAge = Clock.System.now().toEpochMilliseconds() - existingFile.uploadedAt.toEpochMilliseconds()
            
            if (maxCacheAge != null && fileAge > maxCacheAge) {
                // File is expired, re-upload
                logger.debug("Existing file {} is expired (age: {} ms), re-uploading", existingFile.name, fileAge)
                geminiFileSearchService.deleteFile(storeInfo.name, existingFile.name)
                val newFile = geminiFileSearchService.uploadFile(
                    storeName = storeInfo.name,
                    fileBytes = fileBytes,
                    mimeType = mimeType,
                    sourceUrl = url,
                    fileHash = fileHash
                )
                Pair(newFile, true)
            } else {
                // File is fresh, skip upload
                logger.debug("Existing file {} is fresh, skipping upload", existingFile.name)
                Pair(existingFile, false)
            }
        } else {
            // New file, upload
            logger.debug("No existing file with hash {}, uploading", fileHash)
            val newFile = geminiFileSearchService.uploadFile(
                storeName = storeInfo.name,
                fileBytes = fileBytes,
                mimeType = mimeType,
                sourceUrl = url,
                fileHash = fileHash
            )
            Pair(newFile, true)
        }

        // Query the file search store
        val searchResult = geminiFileSearchService.queryStore(
            storeName = storeInfo.name,
            query = query,
            maxAgeMs = maxCacheAge
        )

        // Record token usage
        tokenUsageService.recordTokenUsage(
            sessionId = sessionId,
            agentName = "FileIngestionService.queryStore",
            modelName = searchResult.tokenUsage.modelName,
            promptTokens = searchResult.tokenUsage.promptTokens,
            outputTokens = searchResult.tokenUsage.outputTokens,
            totalTokens = searchResult.tokenUsage.totalTokens
        )

        // Build markdown and citations from search results
        val (markdown, citations) = buildMarkdownWithCitations(searchResult.chunks, url)

        logger.debug(
            "File ingestion complete: {} uploaded, {} chunks, {} chars markdown",
            if (wasUploaded) "newly" else "already",
            searchResult.chunks.size,
            markdown.length
        )

        return FileIngestResult(
            fileInfo = fileInfo,
            wasUploaded = wasUploaded,
            searchResult = searchResult,
            markdown = markdown,
            citations = citations
        )
    }

    private fun calculateHash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(bytes)
        return Base64.encode(hashBytes)
    }

    private fun extractDomain(url: String): String {
        return try {
            val uri = URI(url)
            val host = uri.host?.lowercase() ?: throw IllegalArgumentException("URL has no host: $url")
            host
        } catch (e: Exception) {
            logger.warn("Failed to extract domain from URL: {}, using normalized URL", url)
            normalizeUrlService.normalize(url) ?: url
        }
    }

    private fun buildMarkdownWithCitations(
        chunks: List<FileSearchChunk>,
        defaultSourceUrl: String
    ): Pair<String, List<FileCitation>> {
        if (chunks.isEmpty()) {
            return Pair("", emptyList())
        }

        val markdown = StringBuilder()
        val citations = mutableListOf<FileCitation>()

        chunks.forEachIndexed { index, chunk ->
            val sourceUrl = chunk.sourceUrl.ifBlank { defaultSourceUrl }
            val fileName = chunk.fileName.ifBlank { "Document" }

            // Add content with citation reference
            if (chunk.content.isNotBlank()) {
                markdown.appendLine(chunk.content)
                markdown.appendLine()
                markdown.appendLine("*Source: [$fileName]($sourceUrl)*")
                markdown.appendLine()

                citations.add(
                    FileCitation(
                        fileName = fileName,
                        sourceUrl = sourceUrl,
                        content = chunk.content
                    )
                )
            }
        }

        return Pair(markdown.toString().trim(), citations)
    }
}

