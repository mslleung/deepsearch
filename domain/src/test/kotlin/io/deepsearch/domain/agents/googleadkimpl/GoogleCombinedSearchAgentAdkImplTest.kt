package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.agents.IGoogleCombinedSearchAgent
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

class GoogleCombinedSearchAgentAdkImplTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IGoogleCombinedSearchAgent>()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `combined search returns content and at least one source`() = runTest(testCoroutineDispatcher) {
        // Given
        val query = SearchQuery(
            query = "what servants are S+ tier?",
            url = "https://appmedia.jp/fategrandorder/96261"
        )

        // When
        val output = agent.generate(IGoogleCombinedSearchAgent.GoogleCombinedSearchInput(query))

        // Then
        val result = output.searchResult
        assertTrue(result.content.isNotBlank(), "content should not be blank")
        assertTrue(result.sources.isNotEmpty(), "should include at least one cited source")
    }
}


