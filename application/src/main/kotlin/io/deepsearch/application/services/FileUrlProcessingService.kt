package io.deepsearch.application.services

import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.models.valueobjects.WebpageLink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.slf4j.LoggerFactory

interface IFileUrlProcessingService {
    /**
     * Process a supported file (PDF, docx, etc.) using Gemini File Search.
     *
     * Flow:
     * 1. For PDFs: Emit PdfPreviewReady immediately using local PDFTextStripper (fast path)
     * 2. Upload to Gemini File Search (if not cached)
     * 3. Query the file search store for relevant content
     * 4. Emit FileMarkdownExtractionComplete with file search results
     * 5. Discover links from the extracted markdown
     * 6. Emit LinksDiscovered with discovered links
     *
     * @param discoverLinks Lambda to discover links from the extracted markdown.
     *        For relevant links: uses LLM to find query-relevant URLs.
     *        For all links: extracts all URLs using regex/parser.
     */
    fun processFileAsFlow(
        normalizedUrl: String,
        contentTypeResult: ContentTypeResult.SupportedFile,
        query: String,
        maxCacheAge: Long?,
        sessionId: SessionId,
        discoverLinks: suspend (markdown: String) -> List<WebpageLink>
    ): Flow<UrlProcessingEvent>
}

class FileUrlProcessingService(
    private val pdfPreviewService: IPdfPreviewService,
    private val fileSearchService: IFileSearchService
) : IFileUrlProcessingService {

    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun processFileAsFlow(
        normalizedUrl: String,
        contentTypeResult: ContentTypeResult.SupportedFile,
        query: String,
        maxCacheAge: Long?,
        sessionId: SessionId,
        discoverLinks: suspend (markdown: String) -> List<WebpageLink>
    ): Flow<UrlProcessingEvent> = channelFlow {
        logger.debug(
            "Processing file: {} ({} bytes, type: {})",
            normalizedUrl, contentTypeResult.bytes.size, contentTypeResult.mimeType
        )

        val isPdf = contentTypeResult.mimeType.contains("pdf", ignoreCase = true)
        if (isPdf) {
            val previewResult = pdfPreviewService.extract(contentTypeResult.bytes, normalizedUrl, query)
            if (previewResult.extractedText.isNotBlank()) {
                logger.debug(
                    "PDF preview ready for {}: {} pages, {} chars",
                    normalizedUrl, previewResult.pageCount, previewResult.extractedText.length
                )
                send(
                    UrlProcessingEvent.PdfPreviewReady(
                        url = normalizedUrl,
                        extractedText = previewResult.extractedText,
                        title = previewResult.title,
                        pageCount = previewResult.pageCount
                    )
                )
            } else {
                logger.debug("PDF preview extraction returned empty text for {}, skipping preview", normalizedUrl)
            }
        }

        val ingestResult = fileSearchService.ingest(
            url = normalizedUrl,
            fileBytes = contentTypeResult.bytes,
            mimeType = contentTypeResult.mimeType,
            maxCacheAge = maxCacheAge
        )

        logger.debug(
            "File ingestion complete for {}: {} (uploaded: {})",
            normalizedUrl, ingestResult.fileInfo.displayName, ingestResult.wasUploaded
        )

        val queryResult = fileSearchService.query(
            domain = ingestResult.storeInfo.domain,
            query = query,
            sessionId = sessionId
        )

        logger.debug(
            "File query complete for {}: {} chars markdown",
            normalizedUrl, queryResult.markdown.length
        )

        send(
            UrlProcessingEvent.FileMarkdownExtractionComplete(
                url = normalizedUrl,
                markdown = queryResult.markdown,
                title = ingestResult.fileInfo.displayName,
                description = "File from ${ingestResult.fileInfo.sourceUrl}"
            )
        )

        val discoveredLinks = discoverLinks(queryResult.markdown)
        send(UrlProcessingEvent.LinksDiscovered(normalizedUrl, discoveredLinks))
        logger.debug("Link discovery complete for {}: {} links", normalizedUrl, discoveredLinks.size)
    }
}
