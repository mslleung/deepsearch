package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.GoogleCombinedSearchInput
import io.deepsearch.domain.agents.IGoogleCombinedSearchAgent
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.SearchQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertTrue

class GoogleCombinedSearchAgentTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IGoogleCombinedSearchAgent>()

    @Test
    fun `combined search returns answer and sources`() = runTest(testCoroutineDispatcher) {
        // Given
        val query = SearchQuery(
            query = "what servants are S+ tier?",
            url = "https://appmedia.jp/fategrandorder/96261"
        )

        // When
        val output = agent.generate(GoogleCombinedSearchInput(query))

        // Then
        assertTrue(output.answer.isNotBlank(), "answer should not be blank")
        assertTrue(output.answerSources.isNotEmpty(), "should include at least one source")
    }
}


