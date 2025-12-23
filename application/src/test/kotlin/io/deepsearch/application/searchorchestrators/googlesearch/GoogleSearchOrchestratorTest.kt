package io.deepsearch.application.searchorchestrators.googlesearch

import io.deepsearch.application.config.applicationTestModule
import io.deepsearch.application.services.IQuerySessionService
import io.deepsearch.application.services.SearchEvent
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.SearchQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoogleSearchOrchestratorTest : KoinTest {
    private val apiKeyId = ApiKeyId(0)

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(applicationTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val googleSearchOrchestrator by inject<IGoogleSearchOrchestrator>()
    private val querySessionService by inject<IQuerySessionService>()

    /**
     * Helper to collect the flow until completion and return the session ID.
     */
    private suspend fun Flow<SearchEvent>.collectUntilComplete(): QuerySessionId {
        var sessionId: QuerySessionId? = null
        this
            .onEach { event ->
                if (event is SearchEvent.SessionCreated) {
                    sessionId = event.sessionId
                }
            }
            .filterIsInstance<SearchEvent.SessionCompleted>()
            .first()
        return sessionId!!
    }

    @Test
    fun `test sample query on OT&P`() = runTest(testCoroutineDispatcher) {
        // Given
        val searchQuery = SearchQuery(
            rawQuery = "Does the standard body check package include testing Glomerular Filtration Rate - eGfr?",  // no
            url = "https://www.otandp.com/"
        )

        // When
        val sessionId = googleSearchOrchestrator.execute(searchQuery, apiKeyId = apiKeyId).collectUntilComplete()
        val session = querySessionService.getSession(sessionId)

        // Then
        assertEquals(searchQuery.query, session.query)
        assertEquals(searchQuery.url, session.url)
        assertTrue(session.answer?.isNotBlank() == true, "Search result content should not be blank")
    }

    @Test
    fun `test sample query on HelmetKing`() = runTest(testCoroutineDispatcher) {
        // Given
        val url = "https://www.helmetking.com/"
        val searchQuery = SearchQuery(
            rawQuery = "Are there any service package or plans for motorcycle maintenance?",  // yes
            url = url
        )

        // When
        val sessionId = googleSearchOrchestrator.execute(searchQuery, apiKeyId = apiKeyId).collectUntilComplete()
        val session = querySessionService.getSession(sessionId)

        // Then
        assertEquals(searchQuery.query, session.query)
        assertEquals(searchQuery.url, session.url)
        assertTrue(session.answer?.isNotBlank() == true, "Search result content should not be blank")
    }

    @Test
    fun `basic summary on example dot com`() = runTest(testCoroutineDispatcher) {
        // Given
        val url = "https://www.example.com/"
        val searchQuery = SearchQuery(
            rawQuery = "What is the main content of this webpage?",
            url = url
        )

        // When
        val sessionId = googleSearchOrchestrator.execute(searchQuery, apiKeyId = apiKeyId).collectUntilComplete()
        val session = querySessionService.getSession(sessionId)

        // Then
        assertEquals(searchQuery.query, session.query)
        assertEquals(searchQuery.url, session.url)
        assertTrue(session.answer?.isNotBlank() == true, "Search result content should not be blank")
    }

    @Test
    fun `pricing and sla question on vendor site`() = runTest(testCoroutineDispatcher) {
        // Given
        val url = "https://www.egltours.com/"
        val searchQuery = SearchQuery(
            rawQuery = "Find pricing, enterprise plan limits, and SLA details",
            url = url
        )

        // When
        val sessionId = googleSearchOrchestrator.execute(searchQuery, apiKeyId = apiKeyId).collectUntilComplete()
        val session = querySessionService.getSession(sessionId)

        // Then
        assertEquals(searchQuery.query, session.query)
        assertEquals(searchQuery.url, session.url)
        assertTrue(session.answer?.isNotBlank() == true, "Search result content should not be blank for pricing/SLA query")
    }

    @Test
    fun `clearly unrelated query still produces grounded answer using given site`() = runTest(testCoroutineDispatcher) {
        // Given
        val url = "https://www.example.com/"
        val searchQuery = SearchQuery(
            rawQuery = "Who is the men's singles table tennis champion of the 2024 Paris Olympics?",
            url = url
        )

        // When
        val sessionId = googleSearchOrchestrator.execute(searchQuery, apiKeyId = apiKeyId).collectUntilComplete()
        val session = querySessionService.getSession(sessionId)

        // Then
        assertEquals(searchQuery.query, session.query)
        assertEquals(searchQuery.url, session.url)
        assertTrue(session.answer?.isNotBlank() == true, "Search result content should not be blank even for unrelated query")
    }

    @Test
    fun `non english query on example dot com`() = runTest(testCoroutineDispatcher) {
        // Given
        val url = "https://www.example.com/"
        val searchQuery = SearchQuery(
            rawQuery = "這個網站的主要內容是什麼？",
            url = url
        )

        // When
        val sessionId = googleSearchOrchestrator.execute(searchQuery, apiKeyId = apiKeyId).collectUntilComplete()
        val session = querySessionService.getSession(sessionId)

        // Then
        assertEquals(searchQuery.query, session.query)
        assertEquals(searchQuery.url, session.url)
        assertTrue(session.answer?.isNotBlank() == true, "Search result content should not be blank for non-English query")
    }
}
