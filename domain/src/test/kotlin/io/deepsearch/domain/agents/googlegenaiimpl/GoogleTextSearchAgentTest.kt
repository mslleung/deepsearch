package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.GoogleTextSearchInput
import io.deepsearch.domain.agents.IGoogleTextSearchAgent
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

class GoogleTextSearchAgentTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IGoogleTextSearchAgent>()

    @Test
    fun `basic site-restricted search returns at least one source`() = runTest(testCoroutineDispatcher) {
        // Given
        val query = SearchQuery(
            rawQuery = "company overview",
            url = "https://www.egltours.com/"
        )

        // When
        val output = agent.generate(GoogleTextSearchInput(query))

        // Then
        assertTrue(output.answerSources.isNotEmpty(), "should include at least one source")
    }
}