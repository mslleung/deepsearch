package io.deepsearch.application.searchstrategies.agenticbrowsersearch

import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult
import io.deepsearch.application.searchstrategies.ISearchStrategy
import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.TableIdentificationInput
import io.deepsearch.domain.agents.IIconInterpreterAgent
import io.deepsearch.domain.agents.IconInterpretationInput
import io.deepsearch.domain.repositories.IWebpageIconRepository
import io.deepsearch.domain.models.entities.WebpageIconRecord
import org.slf4j.Logger
import org.slf4j.LoggerFactory

interface IAgenticBrowserSearchStrategy : ISearchStrategy {
    override suspend fun execute(searchQuery: SearchQuery): SearchResult
}

/**
 * Agentic search that iteratively extracts answers from the current page and
 * proposes actions to surface more relevant information if needed.
 */
class AgenticBrowserSearchStrategy(
    private val browserPool: IBrowserPool,
    private val tableIdentificationAgent: ITableIdentificationAgent,
    private val iconInterpreterAgent: IIconInterpreterAgent,
    private val webpageIconRepository: IWebpageIconRepository,
) : IAgenticBrowserSearchStrategy {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun execute(searchQuery: SearchQuery): SearchResult {
        val url = searchQuery.url
        val browser = browserPool.acquireBrowser()
        try {
            val context = browser.createContext()
            val page = context.newPage()
            page.navigate(url)

            val title = page.getTitle()
            val description = page.getDescription()

            // Phase 1: extract and interpret icons
            val icons = page.extractIcons()
            logger.debug("Icon extraction yielded {} candidates", icons.size)
            var interpretedCount = 0
            for (icon in icons) {
                val existing = webpageIconRepository.findByUrlAndHash(url, icon.imageBytesHash)
                if (existing == null) {
                    val output = iconInterpreterAgent.generate(
                        IconInterpretationInput(
                            bytes = icon.bytes,
                            mimeType = icon.mimeType,
                            context = mapOf("selector" to icon.selector)
                        )
                    )
                    val record = WebpageIconRecord(
                        selector = icon.selector,
                        imageBytesHash = icon.imageBytesHash,
                        mimeType = icon.mimeType,
                        jpegBytes = icon.bytes,
                        label = output.label,
                        confidence = output.confidence,
                        hints = output.hints
                    )
                    webpageIconRepository.upsert(url, record)
                    interpretedCount++
                }
            }
            logger.debug("Interpreted {} new icons; {} already cached", interpretedCount, icons.size - interpretedCount)

            // Phase 2: table identification (existing logic)
            val screenshot = page.takeScreenshot()
            val tableInput = TableIdentificationInput(screenshot.bytes, screenshot.mimeType)
            val tableOutput = tableIdentificationAgent.generate(tableInput)
            val tables = tableOutput.tables
            logger.debug("Identified {} table xpaths: {}", tables.size, tables)

            // v1 content: basic page summary
            val summary = buildString {
                appendLine("Title: ${title}")
                if (!description.isNullOrBlank()) {
                    appendLine("Description: ${description}")
                }
                appendLine("Icons processed: ${icons.size}, new: ${interpretedCount}")
                appendLine("Tables identified: ${tables.size}")
            }.trim()

            return SearchResult(
                originalQuery = searchQuery,
                content = summary,
                sources = listOf(url)
            )
        } finally {
            browser.close()
        }
    }

//    private data class WebpageAnalysisResults(
//        val url: String,
//        val answer: String?,
////        val actions: List<IAgenticBrowserAction>
//    )
//
//    private suspend fun analyzeWebpage(query: String, url: String): WebpageAnalysisResults {
//        // TODO use agents to analyze the webpage and return the results
//        // 1. try to extract an answer from the current page using an llm agent
//        // 2. look for additional actions that may yield more information
//        // 3. synthesize the WebpageAnalysisResults and return
//        TODO()
//    }
}