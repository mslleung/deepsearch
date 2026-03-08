package io.deepsearch.application.services

import io.deepsearch.domain.models.valueobjects.FileSearchChunk
import io.deepsearch.domain.models.valueobjects.FileSearchResult
import io.deepsearch.domain.models.valueobjects.FileSearchStoreInfo
import io.deepsearch.domain.models.valueobjects.GeminiFileInfo
import io.deepsearch.domain.models.valueobjects.SessionId
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
 * Result of file ingestion.
 */
@OptIn(ExperimentalTime::class)
data class FileIngestResult(
    val fileInfo: GeminiFileInfo,
    val storeInfo: FileSearchStoreInfo,
    val wasUploaded: Boolean        // true if file was newly uploaded, false if existing
)

/**
 * Result of querying a file search store.
 */
data class FileQueryResult(
    val searchResult: FileSearchResult,
    val markdown: String,           // Combined markdown from search results
    val citations: List<FileCitation>
)

/**
 * A citation from a file search result.
 *
 * For web-sourced files: sourceUrl is set, sourceText is null.
 * For file search grounding (no document attribution from API): sourceUrl is null, sourceText
 * contains a truncated excerpt of the grounding chunk for customer-facing citation.
 */
data class FileCitation(
    val fileName: String,
    val sourceUrl: String?,
    val sourceText: String?,
    val content: String
)

/**
 * Service interface for file-based RAG using Gemini File Search.
 *
 * This is the application layer interface to file RAG capabilities.
 * Uses GeminiFileSearchService (domain layer) for the actual file search operations.
 */
interface IFileSearchService {

    /**
     * Ingest a file into Gemini File Search for the domain extracted from the URL.
     *
     * Handles deduplication via file hash:
     * - If file with same hash exists and is not expired, skips upload
     * - If file is new or expired, uploads with metadata
     *
     * @param url Original URL where the file was found (used to extract domain)
     * @param fileBytes The file content
     * @param mimeType MIME type of the file
     * @param maxCacheAge Maximum age in milliseconds for cached files (null = no expiry)
     * @return FileIngestResult containing file info and upload status
     */
    suspend fun ingest(
        url: String,
        fileBytes: ByteArray,
        mimeType: String,
        maxCacheAge: Long?
    ): FileIngestResult

    /**
     * Query a domain's file search store for relevant content.
     *
     * @param domain The domain to query (e.g., "docs.example.com")
     * @param query The search query
     * @param sessionId Session ID for token tracking
     * @param maxAgeMs Optional maximum age in milliseconds; files uploaded before
     *                 this threshold are filtered out server-side
     * @return FileQueryResult containing search results, markdown, and citations
     */
    suspend fun query(
        domain: String,
        query: String,
        sessionId: SessionId,
        maxAgeMs: Long? = null
    ): FileQueryResult
}

/**
 * Service for file-based RAG using Gemini File Search.
 *
 * Provides file ingestion with deduplication and querying capabilities.
 * Uses one file search store per domain.
 */
@OptIn(ExperimentalTime::class, ExperimentalEncodingApi::class)
class FileSearchService(
    private val geminiFileSearchService: IGeminiFileSearchService,
    private val normalizeUrlService: INormalizeUrlService,
    private val tokenUsageService: ILlmTokenUsageService
) : IFileSearchService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun ingest(
        url: String,
        fileBytes: ByteArray,
        mimeType: String,
        maxCacheAge: Long?
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

        logger.debug(
            "File ingestion complete: {} uploaded for domain {}",
            if (wasUploaded) "newly" else "already",
            domain
        )

        return FileIngestResult(
            fileInfo = fileInfo,
            storeInfo = storeInfo,
            wasUploaded = wasUploaded
        )
    }

    override suspend fun query(
        domain: String,
        query: String,
        sessionId: SessionId,
        maxAgeMs: Long?
    ): FileQueryResult {
        logger.debug("Querying file search for domain {}: '{}' (maxAgeMs: {})", domain, query, maxAgeMs)

        // Get the store for this domain
        val storeInfo = geminiFileSearchService.findStore(domain)
            ?: throw IllegalStateException("No file search store found for domain: $domain")

        // Query the file search store with age filter
        val searchResult = geminiFileSearchService.queryStore(
            storeName = storeInfo.name,
            query = query,
            maxAgeMs = maxAgeMs
        )

        // Record token usage
        tokenUsageService.recordTokenUsage(
            sessionId = sessionId,
            agentName = "FileSearchService.query",
            modelName = searchResult.tokenUsage.modelName,
            promptTokens = searchResult.tokenUsage.promptTokens,
            outputTokens = searchResult.tokenUsage.outputTokens,
            totalTokens = searchResult.tokenUsage.totalTokens
        )

        // Build markdown and citations from search results
        val (markdown, citations) = buildMarkdownWithCitations(searchResult.chunks, storeInfo.domain)

        logger.debug(
            "File search complete for domain {}: {} chunks, {} chars markdown",
            domain, searchResult.chunks.size, markdown.length
        )

        return FileQueryResult(
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
        domain: String
    ): Pair<String, List<FileCitation>> {
        if (chunks.isEmpty()) {
            return Pair("", emptyList())
        }

        val markdown = StringBuilder()
        val citations = mutableListOf<FileCitation>()

        chunks.forEach { chunk ->
            if (chunk.content.isBlank()) return@forEach

            val hasSourceUrl = chunk.sourceUrl.isNotBlank()
            val fileName = chunk.fileName.ifBlank { "Document" }

            markdown.appendLine(chunk.content)
            markdown.appendLine()

            if (hasSourceUrl) {
                markdown.appendLine("*Source: [$fileName](${chunk.sourceUrl})*")
            } else {
                markdown.appendLine("*Source: File search result*")
            }
            markdown.appendLine()

            citations.add(
                FileCitation(
                    fileName = fileName,
                    sourceUrl = if (hasSourceUrl) chunk.sourceUrl else null,
                    sourceText = if (!hasSourceUrl) chunk.content else null,
                    content = chunk.content
                )
            )
        }

        return Pair(markdown.toString().trim(), citations)
    }
}

