package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.agents.IWebpageExtractAnswerAgent
import io.deepsearch.domain.models.valueobjects.SearchResult

/**
 * Placeholder ADK-backed implementation. Wire to your LLM tool of choice.
 * For now, returns NotFound to allow orchestration to proceed.
 */
class WebpageExtractAnswerAgentAdkImpl : IWebpageExtractAnswerAgent {
    override suspend fun generate(input: IWebpageExtractAnswerAgent.Input): IWebpageExtractAnswerAgent.Output {
        // TODO: Implement real LLM call. Minimal heuristic fallback:
        val mt = input.pageInformation.mainText
        if (!mt.isNullOrBlank() && mt.length > 10) {
            return IWebpageExtractAnswerAgent.Output.Found(
                searchResult = SearchResult(
                    originalQuery = input.searchQuery,
                    content = mt.take(800),
                    sources = listOf(input.pageInformation.url)
                ),
                confidence = 0.4,
                evidenceSnippets = listOf(mt.take(200))
            )
        }
        return IWebpageExtractAnswerAgent.Output.NotFound(
            reason = "Insufficient content visible"
        )
    }
}


