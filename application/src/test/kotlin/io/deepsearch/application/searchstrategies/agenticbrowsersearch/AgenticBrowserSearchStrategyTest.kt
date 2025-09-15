package io.deepsearch.application.searchstrategies.agenticbrowsersearch

import io.deepsearch.application.config.applicationTestModule
import io.deepsearch.domain.models.valueobjects.SearchQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@EnabledIfEnvironmentVariable(named = "GOOGLE_API_KEY", matches = ".+")
class AgenticBrowserSearchStrategyTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(applicationTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agenticBrowserSearchStrategy by inject<IAgenticBrowserSearchStrategy>()

    @Test
    fun `test sample query on OT&P`() = runTest(testCoroutineDispatcher) {
        // Given
        val searchQuery = SearchQuery(
            query = "Does the standard body check package include testing Glomerular Filtration Rate - eGfr?",  // no
            url = "https://www.otandp.com/"
        )

        // When
        val result = agenticBrowserSearchStrategy.execute(searchQuery)

        // Then
        assertEquals(searchQuery, result.originalQuery)
        assertTrue(result.content.isNotBlank(), "Search result content should not be blank")
        assertTrue(!result.sources.isEmpty(), "Sources should not be empty")
    }

    @Test
    fun `test sample query on HelmetKing`() = runTest(testCoroutineDispatcher) {
        // Given
        val url = "https://www.helmetking.com/"
        val searchQuery = SearchQuery(
            query = "Are there any service package or plans for motorcycle maintenance?",  // yes
            url = url
        )

        // When
        val result = agenticBrowserSearchStrategy.execute(searchQuery)

        // Then
        assertEquals(searchQuery, result.originalQuery)
        assertTrue(result.content.isNotBlank(), "Search result content should not be blank")
        assertTrue(!result.sources.isEmpty(), "Sources should not be empty")
    }

    @Test
    fun `basic summary on example dot com`() = runTest(testCoroutineDispatcher) {
        // Given
        val url = "https://www.example.com/"
        val searchQuery = SearchQuery(
            query = "What is the main content of this webpage?",
            url = url
        )

        // When
        val result = agenticBrowserSearchStrategy.execute(searchQuery)

        // Then
        assertEquals(searchQuery, result.originalQuery)
        assertTrue(result.content.isNotBlank(), "Search result content should not be blank")
        assertTrue(result.sources.contains(url), "Sources should include the target URL")
    }

    @Test
    fun `pricing and sla question on vendor site`() = runTest(testCoroutineDispatcher) {
        // Given
        val url = "https://www.egltours.com/"
        val searchQuery = SearchQuery(
            query = "Find pricing, enterprise plan limits, and SLA details",
            url = url
        )

        // When
        val result = agenticBrowserSearchStrategy.execute(searchQuery)

        // Then
        assertEquals(searchQuery, result.originalQuery)
        assertTrue(result.content.isNotBlank(), "Search result content should not be blank for pricing/SLA query")
        assertTrue(result.sources.contains(url), "Sources should include the target URL")
    }

    @Test
    fun `clearly unrelated query still produces grounded answer using given site`() = runTest(testCoroutineDispatcher) {
        // Given
        val url = "https://www.example.com/"
        val searchQuery = SearchQuery(
            query = "Who is the men's singles table tennis champion of the 2024 Paris Olympics?",
            url = url
        )

        // When
        val result = agenticBrowserSearchStrategy.execute(searchQuery)

        // Then
        assertEquals(searchQuery, result.originalQuery)
        assertTrue(result.content.isNotBlank(), "Search result content should not be blank even for unrelated query")
        // The combined agent always grounds sources to the provided URL for now
        assertEquals(listOf(url), result.sources, "Sources should reflect the target URL used for context")
    }

    @Test
    fun `non english query on example dot com`() = runTest(testCoroutineDispatcher) {
        // Given
        val url = "https://www.example.com/"
        val searchQuery = SearchQuery(
            query = "這個網站的主要內容是什麼？",
            url = url
        )

        // When
        val result = agenticBrowserSearchStrategy.execute(searchQuery)

        // Then
        assertEquals(searchQuery, result.originalQuery)
        assertTrue(result.content.isNotBlank(), "Search result content should not be blank for non-English query")
        assertTrue(result.sources.contains(url), "Sources should include the target URL")
    }
}


