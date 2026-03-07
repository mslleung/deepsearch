package io.deepsearch.application.services

import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.SemanticElements
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.services.IHtmlToMarkdownService
import io.deepsearch.domain.services.IJsoupDomService
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.system.measureTimeMillis

/**
 * Result of webpage indexing. Contains markdown from DOM extraction,
 * including non-visible content (accordion bodies, tab panels) for thorough vector indexing.
 */
data class WebpageIndexResult(
    val markdown: String,
    val title: String?,
    val description: String?
)

interface IWebpageIndexingService {
    /**
     * Produce index-quality markdown from a loaded browser page.
     *
     * Pipeline: inject IDs -> capture (snapshot + screenshot) -> visual identification
     * -> semantic element removal -> HTML-to-markdown.
     *
     * Icons and tables are NOT interpreted — the raw text is sufficient for vector
     * search routing. Query-time accuracy is handled by agentic VLM search.
     */
    suspend fun indexWebpage(
        page: IBrowserPage,
        sessionId: SessionId
    ): WebpageIndexResult

    /**
     * Index from pre-captured browser data (no browser access needed).
     * Used during query-time background indexing: the orchestrator performs a quick capture
     * (~0.5s), then this method processes the captured data server-side while the browser
     * is freed for agentic search.
     */
    suspend fun indexFromCapturedData(
        capturedData: QuickCaptureData,
        sessionId: SessionId
    ): WebpageIndexResult
}

/**
 * Data captured during a quick browser pass, sufficient for server-side indexing.
 */
data class QuickCaptureData(
    val snapshot: IBrowserPage.PageSnapshotWithMetadata,
    val screenshot: IBrowserPage.Screenshot
)

private val quickCaptureLogger: Logger = LoggerFactory.getLogger("io.deepsearch.application.services.QuickCapture")

/**
 * Captures essential browser data quickly (~0.5s) without closing the page.
 * The page remains open for subsequent agentic search.
 *
 * Screenshot failure is non-fatal: the pipeline will skip visual identification
 * (boilerplate removal) and produce markdown from the full HTML instead.
 */
suspend fun quickCapture(page: IBrowserPage): QuickCaptureData = coroutineScope {
    page.injectStableIds()
    val snapshotDeferred = async { page.capturePageSnapshot() }
    val screenshotDeferred = async {
        page.takeFullPageScreenshot()
    }
    QuickCaptureData(snapshotDeferred.await(), screenshotDeferred.await())
}

/**
 * Produces index-quality markdown for vector search routing.
 *
 * Deliberately lightweight compared to [WebpageExtractionService]:
 * - No icon interpretation (icon text like "{checkmark icon}" doesn't help embeddings)
 * - No table interpretation (raw table text is sufficient for semantic matching)
 * - Visual identification is kept solely for removing boilerplate (header, footer, nav, etc.)
 *   that would pollute embeddings with repeated site-wide noise.
 */
class WebpageIndexingService(
    private val visualIdentificationService: IVisualIdentificationService,
    private val jsoupDomService: IJsoupDomService,
    private val htmlToMarkdownService: IHtmlToMarkdownService
) : IWebpageIndexingService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun indexWebpage(
        page: IBrowserPage,
        sessionId: SessionId
    ): WebpageIndexResult {
        val result: WebpageIndexResult
        val totalDuration = measureTimeMillis {
            val capturedData: QuickCaptureData
            val captureDuration = measureTimeMillis {
                capturedData = quickCapture(page)
            }
            logger.debug("Browser capture complete in {} ms", captureDuration)

            result = indexFromCapturedData(capturedData, sessionId)
        }
        logger.info("Webpage indexing (with capture) completed in {} ms", totalDuration)
        return result
    }

    override suspend fun indexFromCapturedData(
        capturedData: QuickCaptureData,
        sessionId: SessionId
    ): WebpageIndexResult {
        val result: WebpageIndexResult
        val totalDuration = measureTimeMillis {
            result = runPipeline(capturedData, sessionId)
        }
        logger.info(
            "Webpage indexing completed in {} ms: {} chars markdown",
            totalDuration, result.markdown.length
        )
        return result
    }

    private suspend fun runPipeline(
        data: QuickCaptureData,
        sessionId: SessionId
    ): WebpageIndexResult {
        val snapshot = data.snapshot
        val screenshot = data.screenshot

        // Phase 1: Layout identification (lightweight -- only header/footer/nav/breadcrumb)
        val semanticElements: SemanticElements
        val layoutDuration = measureTimeMillis {
            semanticElements = visualIdentificationService.identifyLayoutElements(sessionId, snapshot, screenshot)
        }
        logger.debug("Layout identification: {} ms", layoutDuration)

        // Phase 2: DOM processing (remove boilerplate, convert to markdown)
        val rawMarkdown: String
        val domDuration = measureTimeMillis {
            val jsoupDoc = Jsoup.parse(snapshot.html)

            jsoupDomService.removeElements(jsoupDoc, collectLayoutDataIdSelectors(semanticElements))
            jsoupDomService.cleanupForMarkdownConversion(jsoupDoc)
            rawMarkdown = htmlToMarkdownService.convert(jsoupDoc.html())
        }
        logger.debug("DOM processing: {} ms, {} chars raw markdown", domDuration, rawMarkdown.length)

        val markdown = buildString {
            appendLine("URL: ${snapshot.url}")
            if (snapshot.title.isNotBlank()) {
                appendLine("Title: ${snapshot.title}")
            }
            if (!snapshot.description.isNullOrBlank()) {
                appendLine("Description: ${snapshot.description}")
            }
            appendLine()
            append(rawMarkdown)
        }

        return WebpageIndexResult(
            markdown = markdown,
            title = snapshot.title.takeIf { it.isNotBlank() },
            description = snapshot.description?.takeIf { it.isNotBlank() }
        )
    }

    private fun collectLayoutDataIdSelectors(semanticElements: SemanticElements): List<String> {
        return buildList {
            semanticElements.header?.let { add("[data-ds-id=\"${it.dataId}\"]") }
            semanticElements.footer?.let { add("[data-ds-id=\"${it.dataId}\"]") }
            semanticElements.navSidebar?.let { add("[data-ds-id=\"${it.dataId}\"]") }
            semanticElements.breadcrumb?.let { add("[data-ds-id=\"${it.dataId}\"]") }
        }
    }
}
