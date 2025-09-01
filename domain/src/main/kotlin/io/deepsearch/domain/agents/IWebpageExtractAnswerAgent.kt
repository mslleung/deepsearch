package io.deepsearch.domain.agents

import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult

/**
 * Agent that attempts to extract a direct answer from the CURRENT webpage view,
 * given the user's search query and the human-oriented page information.
 *
 * This should use an LLM (vision-capable if available) grounded by
 * [IBrowserPage.PageInformation] and a screenshot.
 */
interface IWebpageExtractAnswerAgent {

    data class Input(
        val searchQuery: SearchQuery,
        val pageInformation: IBrowserPage.PageInformation,
        val screenshotBytes: ByteArray
    )

    sealed interface Output {
        data class Found(
            val searchResult: SearchResult,
            val confidence: Double,
            val evidenceSnippets: List<String>
        ) : Output

        data class NotFound(
            val reason: String,
            val hints: List<String> = emptyList()
        ) : Output
    }

    suspend fun generate(input: Input): Output
}


