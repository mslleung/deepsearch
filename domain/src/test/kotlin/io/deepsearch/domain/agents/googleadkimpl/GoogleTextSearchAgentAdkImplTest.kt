package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.agents.GoogleTextSearchInput
import io.deepsearch.domain.agents.IGoogleTextSearchAgent
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.SearchQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertTrue

class GoogleTextSearchAgentAdkImplTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IGoogleTextSearchAgent>()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `basic site-restricted search returns content and at least one source`() = runTest(testCoroutineDispatcher) {
        // Given
        val query = SearchQuery(
            query = "company overview",
            url = "https://www.egltours.com/"
        )

        // When
        val output = agent.generate(GoogleTextSearchInput(query))

        // Then
        val result = output.searchResult
        assertTrue(result.content.isNotBlank(), "content should not be blank")
        assertTrue(result.sources.isNotEmpty(), "should include at least one cited source")
    }
}