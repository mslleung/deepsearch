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
     * Markdown extracted from a non-HTML file (PDF, docx, etc.) via Gemini File Search.
     * Not used for webpage content — webpages go through [AgenticAnswer] in query sessions
     * or get indexed separately for semantic search in periodic indexing.
     */
    data class FileMarkdown(
        override val url: String,
        override val title: String?,
        override val description: String?,
        val markdown: String
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

    /**
     * Direct answer from VLM-driven agentic in-page search.
     * Bypasses normal source evaluation — converted directly to EvaluatedSource.
     */
    data class AgenticAnswer(
        override val url: String,
        override val title: String?,
        override val description: String?,
        val answer: String?,
        val evidence: String?,
        val contentDate: String?,
        val observations: List<String>,
        val success: Boolean
    ) : UrlContentResult()
}
