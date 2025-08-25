package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.agents.IGoogleTextSearchAgent
import io.deepsearch.domain.agents.IGoogleUrlContextSearchAgent
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.SearchQuery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.getValue
import kotlin.test.assertTrue

class GoogleUrlContextSearchAgentAdkImplTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val agent by inject<IGoogleUrlContextSearchAgent>()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `url-context search returns content and at least one source`() = runTest {
        // Given
        val query = "what servants are S+ tier?"
        val urls = listOf("https://appmedia.jp/fategrandorder/96261")

        // When
        val result = agent.generate(
            IGoogleUrlContextSearchAgent.GoogleUrlContextSearchInput(
                query = query,
                urls = urls
            )
        )

        // Then
        assertTrue(result.content.isNotBlank(), "content should not be blank")
        assertTrue(result.sources.isNotEmpty(), "should include at least one cited source")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `url-context search clearly state conflicting sources`() = runTest {
        // Given
        val query = "Does the standard body check package include testing Glomerular Filtration Rate - eGfr?"
        val urls = listOf("https://www.otandp.com/body-check/", "https://www.otandp.com/body-check/standard")

        // When
        val result = agent.generate(
            IGoogleUrlContextSearchAgent.GoogleUrlContextSearchInput(
                query = query,
                urls = urls
            )
        )

        // Then
        assertTrue(result.content.isNotBlank(), "content should not be blank")
        assertTrue(result.sources.isNotEmpty(), "should include at least one cited source")
    }
}


