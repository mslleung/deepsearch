package io.deepsearch.domain.models.valueobjects

/**
 * Sealed class representing content extracted from a URL.
 * Used to unify the preview path (sentences) and main path (Markdown) in the orchestrator.
 */
sealed class UrlContentResult {
    abstract val url: String
    abstract val title: String?
    abstract val description: String?

    /**
     * Fast preview for early evaluation.
     * 
     * Contains extracted sentences (plain text), NOT raw HTML.
     * Sentences are extracted using ICU4J for multilingual support.
     * This naturally filters out tabular data since table cells are 
     * short fragments, not complete sentences.
     * 
     * Note: The field is named `cleanedHtml` for backward compatibility,
     * but it actually contains extracted sentences (plain text).
     */
    data class HtmlPreview(
        override val url: String,
        override val title: String?,
        override val description: String?,
        /** Extracted sentences from the page. Named for backward compatibility but contains plain text. */
        val cleanedHtml: String
    ) : UrlContentResult()

    /**
     * Full markdown extraction with LLM processing.
     * Contains properly formatted markdown with tables, images, etc.
     */
    data class FullMarkdown(
        override val url: String,
        override val title: String?,
        override val description: String?,
        val markdown: String,
        /** Mapping of image numbers to hash IDs for new markdown format. Null for legacy format. */
        val imageMapping: Map<String, String>? = null
    ) : UrlContentResult()

    /**
     * Fast PDF preview for early evaluation.
     * Contains text extracted via PDFTextStripper (local, no Gemini).
     * Used in the preview path similar to HtmlPreview.
     */
    data class PdfPreview(
        override val url: String,
        override val title: String?,
        override val description: String?,
        val extractedText: String,
        val pageCount: Int
    ) : UrlContentResult()
}
