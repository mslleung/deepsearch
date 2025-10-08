package io.deepsearch.application.services

import io.deepsearch.domain.agents.IQueryAnsweringAgent
import io.deepsearch.domain.agents.QueryAnsweringInput
import io.deepsearch.domain.models.entities.QueryAnswer
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult
import io.deepsearch.domain.repositories.IQueryAnswerRepository
import java.security.MessageDigest

interface IQueryAnsweringService {
    suspend fun answerQuery(screenshotBytes: ByteArray, html: String, searchQuery: SearchQuery): SearchResult
}

class QueryAnsweringService(
    private val queryAnsweringAgent: IQueryAnsweringAgent,
    private val queryAnswerRepository: IQueryAnswerRepository
) : IQueryAnsweringService {

    /**
     * Answers a search query using webpage screenshot and HTML content.
     * Results are cached in the repository to avoid repeated calls with the same query and content.
     */
    override suspend fun answerQuery(screenshotBytes: ByteArray, html: String, searchQuery: SearchQuery): SearchResult {
        // Create a hash from query, URL, screenshot, and HTML for caching
        val queryHash = createQueryHash(searchQuery, screenshotBytes, html)

        val existing = queryAnswerRepository.findByHash(queryHash)
        if (existing != null) {
            return SearchResult(
                originalQuery = searchQuery,
                content = existing.answer,
                sources = listOf(searchQuery.url)
            )
        }

        val agentInput = QueryAnsweringInput(
            screenshotBytes = screenshotBytes,
            html = html,
            searchQuery = searchQuery
        )

        val agentOutput = queryAnsweringAgent.generate(agentInput)
        val answer = agentOutput.answer

        queryAnswerRepository.upsert(
            QueryAnswer(
                queryHash = queryHash,
                answer = answer
            )
        )

        return SearchResult(
            originalQuery = searchQuery,
            content = answer,
            sources = listOf(searchQuery.url)
        )
    }

    private fun createQueryHash(searchQuery: SearchQuery, screenshotBytes: ByteArray, html: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(searchQuery.query.toByteArray())
        digest.update(searchQuery.url.toByteArray())
        digest.update(screenshotBytes)
        digest.update(html.toByteArray())
        return digest.digest()
    }
}
