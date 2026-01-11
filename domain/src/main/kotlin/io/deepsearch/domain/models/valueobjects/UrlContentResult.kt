package io.deepsearch.domain.models.valueobjects

/**
 * Sealed class representing content extracted from a URL.
 * Used to unify the preview path (HTML) and main path (Markdown) in the orchestrator.
 */
sealed class UrlContentResult {
    abstract val url: String
    abstract val title: String?
    abstract val description: String?

    /**
     * Fast HTML preview for early evaluation.
     * Contains cleaned HTML, NOT markdown.
     */
    data class HtmlPreview(
        override val url: String,
        override val title: String?,
        override val description: String?,
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
}
