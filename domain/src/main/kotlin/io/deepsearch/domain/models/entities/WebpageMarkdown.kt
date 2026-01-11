package io.deepsearch.domain.models.entities

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
data class WebpageMarkdown(
    val url: String, // normalized URL
    val title: String?,
    val description: String?,
    val markdown: String?,
    val cleanedLinkRelevanceHtml: String? = null, // Cleaned HTML for link discovery (~10-30KB vs ~500KB-1MB raw)
    val cleanedPreviewHtml: String? = null, // Pre-cleaned HTML for preview path (avoids CPU-heavy Jsoup processing)
    val httpStatus: Int?,
    val httpReason: String?,
    val mimeType: String?,
    val embedding: List<Float>? = null, // 1536-dimensional embedding vector for semantic search
    val isPreview: Boolean = false, // true if content is from simple text extraction (no LLM processing)
    /**
     * For FILE type URLs: Gemini File Search document resource name.
     * Format: "fileSearchStores/{store}/documents/{doc}"
     * Used to delete files from Gemini when the cached content is deleted.
     * Null for HTML type URLs.
     */
    val fileSearchDocumentName: String? = null,
    /**
     * Mapping of image numbers to original image hash IDs.
     * Format: {"1": "img-abc123", "2": "img-def456"}
     * Used to resolve ![description](#img-N) references in markdown back to actual image hashes.
     */
    val imageMapping: Map<String, String>? = null,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
    var version: Long = 0
)

