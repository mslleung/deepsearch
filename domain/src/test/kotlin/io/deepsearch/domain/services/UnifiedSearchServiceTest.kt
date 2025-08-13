package io.deepsearch.domain.services

import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.SearchQuery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertTrue

class UnifiedSearchServiceTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val unifiedSearchService by inject<IUnifiedSearchService>()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `performSearch should return content about main webpage information for www example com`() = runTest {
        // Given
        val searchQuery = SearchQuery(
            query = "What is the main content of this webpage?",
            url = "https://www.example.com"
        )

        // When
        val result = unifiedSearchService.performSearch(searchQuery)

        // Then
        assertTrue(result.content.isNotBlank(), "Search result should not be blank")
    }
}
