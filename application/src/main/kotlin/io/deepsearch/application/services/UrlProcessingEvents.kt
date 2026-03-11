package io.deepsearch.application.services

import io.deepsearch.domain.models.valueobjects.WebpageLink

/**
 * Events emitted during URL processing.
 * Links may be discovered incrementally (from HTML analysis and agentic navigation),
 * then content is extracted. Multiple [LinksDiscovered] events can be emitted per URL.
 * If processing fails, UrlProcessingException is thrown and should be caught using Flow.catch{}.
 */
sealed interface UrlProcessingEvent {
    val url: String

    data class LinksDiscovered(
        override val url: String,
        val discoveredLinks: List<WebpageLink>,
        val allEvaluatedUrls: Set<String> = emptySet()
    ) : UrlProcessingEvent

    /**
     * Webpage markdown extracted via DOM pipeline (periodic indexing) or from cache.
     * Emitted by [IndexingUrlProcessingService], never by [QueryUrlProcessingService].
     */
    data class MarkdownExtractionComplete(
        override val url: String,
        val markdown: String,
        val title: String?,
        val description: String?,
        val wasCached: Boolean,
        val imageMapping: Map<String, String>? = null
    ) : UrlProcessingEvent

    /**
     * Markdown extracted from a non-HTML file (PDF, docx, etc.) via Gemini File Search.
     * Distinct from [MarkdownExtractionComplete] which is webpage-specific.
     */
    data class FileMarkdownExtractionComplete(
        override val url: String,
        val markdown: String,
        val title: String?,
        val description: String?
    ) : UrlProcessingEvent

    /**
     * Emitted immediately after PDF is downloaded, before Gemini upload.
     * Contains text extracted via PDFTextStripper for preview source evaluation.
     * Only emitted for PDF files (application/pdf).
     */
    data class PdfPreviewReady(
        override val url: String,
        val extractedText: String,
        val title: String?,
        val pageCount: Int
    ) : UrlProcessingEvent

    /**
     * Emitted when agentic in-page search completes.
     * Contains a direct answer from VLM-driven page interaction.
     * Emitted by [QueryUrlProcessingService], never by [IndexingUrlProcessingService].
     */
    data class AgenticSearchComplete(
        override val url: String,
        val answer: String?,
        val evidence: String?,
        val contentDate: String?,
        val observations: List<String>,
        val success: Boolean,
        val imageIds: List<String> = emptyList(),
        val imageDescriptions: Map<String, String> = emptyMap()
    ) : UrlProcessingEvent
}
