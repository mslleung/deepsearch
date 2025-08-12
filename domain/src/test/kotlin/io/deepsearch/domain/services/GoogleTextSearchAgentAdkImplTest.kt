package io.deepsearch.domain.services

import io.deepsearch.domain.agents.IGoogleTextSearchAgent
import io.deepsearch.domain.agents.googleadkimpl.GoogleTextSearchAgentAdkImpl
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.SearchQuery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension

class GoogleTextSearchAgentAdkImplTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

//    private val agent: IGoogleTextSearchAgent by inject<IGoogleTextSearchAgent>()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `basic site-restricted search returns content and at least one source`() = runTest {
        // Given
        val query = SearchQuery(
            query = "company overview",
            url = "https://www.egltours.com/"
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val agent = GoogleTextSearchAgentAdkImpl(dispatcher);

        // When
        val output = agent.generate(IGoogleTextSearchAgent.GoogleTextSearchInput(query))

        // Then
        val result = output.searchResult
        assertTrue(result.content.isNotBlank(), "content should not be blank")
        assertTrue(result.sources.isNotEmpty(), "should include at least one cited source")
    }
}


