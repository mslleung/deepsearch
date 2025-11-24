package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.GoogleSearchLinkDiscoveryInput
import io.deepsearch.domain.agents.IGoogleSearchLinkDiscoveryAgent
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.LinkSource
import io.deepsearch.domain.models.valueobjects.SearchQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoogleSearchLinkDiscoveryAgentTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IGoogleSearchLinkDiscoveryAgent>()

    @Test
    fun `discovers relevant links from site using Google Search`() = runTest(testCoroutineDispatcher) {
        // Given
        val searchQuery = SearchQuery(
            query = "pricing information",
            url = "https://www.egltours.com/"
        )

        // When
        val output = agent.generate(GoogleSearchLinkDiscoveryInput(searchQuery))

        // Then
        assertTrue(output.links.isNotEmpty(), "Should discover links via Google Search")
        output.links.forEach { link ->
            assertEquals(LinkSource.GOOGLE_SEARCH, link.source)
            assertTrue(link.url.startsWith(searchQuery.url), "Link should be from the target site")
            assertTrue(link.reason.isNotBlank(), "Should provide reason for link inclusion")
        }
    }
}
