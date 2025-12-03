package io.deepsearch.domain.models.valueobjects

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Information about a Gemini File Search Store.
 */
data class FileSearchStoreInfo(
    val name: String,           // Gemini store resource name (e.g., "fileSearchStores/abc123")
    val displayName: String,    // Human-readable name
    val domain: String          // Domain this store is associated with
)

/**
 * Information about a file stored in Gemini File Search.
 */
@OptIn(ExperimentalTime::class)
data class GeminiFileInfo(
    val name: String,           // Gemini file resource name
    val displayName: String,    // Human-readable file name
    val mimeType: String,
    val fileHash: String,       // SHA-256 hash stored in metadata
    val sourceUrl: String,      // Original URL stored in metadata
    val uploadedAt: Instant     // Upload timestamp from metadata
)

/**
 * Result of querying a Gemini File Search Store.
 */
data class FileSearchResult(
    val chunks: List<FileSearchChunk>,
    val tokenUsage: TokenUsageMetrics
)

/**
 * A chunk of content retrieved from file search.
 */
data class FileSearchChunk(
    val content: String,        // The actual text content
    val sourceUrl: String,      // Original source URL from metadata
    val fileName: String,       // File display name for citation
    val relevanceScore: Float?  // Optional relevance score if available
)

/**
 * Metadata stored with each file in Gemini File Search.
 */
data class GeminiFileMetadata(
    val fileHash: String,
    val uploadedAtEpochMs: Long,
    val sourceUrl: String
) {
    companion object {
        const val KEY_FILE_HASH = "fileHash"
        const val KEY_UPLOADED_AT = "uploadedAtEpochMs"
        const val KEY_SOURCE_URL = "sourceUrl"

        fun fromMap(map: Map<String, String>): GeminiFileMetadata? {
            val fileHash = map[KEY_FILE_HASH] ?: return null
            val uploadedAt = map[KEY_UPLOADED_AT]?.toLongOrNull() ?: return null
            val sourceUrl = map[KEY_SOURCE_URL] ?: return null
            return GeminiFileMetadata(fileHash, uploadedAt, sourceUrl)
        }
    }

    fun toMap(): Map<String, String> = mapOf(
        KEY_FILE_HASH to fileHash,
        KEY_UPLOADED_AT to uploadedAtEpochMs.toString(),
        KEY_SOURCE_URL to sourceUrl
    )
}

