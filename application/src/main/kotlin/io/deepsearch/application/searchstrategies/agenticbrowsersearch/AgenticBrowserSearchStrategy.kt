package io.deepsearch.application.searchstrategies.agenticbrowsersearch

import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult
import io.deepsearch.application.searchstrategies.ISearchStrategy
import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.TableIdentificationInput
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



            // TODO we need to identify icons on the page, render them as jpeg, use an agent to turn to text and save in a cache
            // 1. Focus on <i> elements for now, we will handle <svg> later.
            // 2. Using a js script, we will first get the jpg base64 render of each icon. First collect all the <i> icons on the page, dedup, then render into Jpeg and convert to base64. Generate a hash on the jpeg bytes and dedup again. In the end we should have a list of objects containing (selector, imageBytesHash and the jpeg bytes in base64 plus a mimetype (image/jpeg)))
            // 3. Return the script results here. Pass each image to an IconInterpreterAgent (new agent) to convert to string.
            // 4. save everything into the db via a WebpageIconRepository.

            val screenshot = page.takeScreenshot()
            val tableInput = TableIdentificationInput(screenshot.bytes, screenshot.mimeType)
            val tableOutput = tableIdentificationAgent.generate(tableInput)
            val tables = tableOutput.tables
            logger.debug("Identified {} table xpaths: {}", tables.size, tables)

//            val script = loadScript("scripts/textContentForExtraction.js")
//            @Suppress("UNCHECKED_CAST")
//            val textContentForExtraction = page.evaluate(script, tables) as String

//            logger.debug(
//                "Extraction complete: totalChars={}",
//                textContentForExtraction.length
//            )
            TODO()  // please ignore this
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