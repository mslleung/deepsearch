package io.deepsearch.domain.services

import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.valueobjects.SearchQuery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgenticSearchServiceTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `performSearch should return content about main webpage information for www example com`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val agenticSearchService = AgenticSearchService(dispatcher)

        // Given
        val searchQuery = SearchQuery(
            query = "What is the main content of this webpage?",
            url = "https://www.example.com"
        )

        // When
        val result = agenticSearchService.performSearch(searchQuery)

        // Then
        assertNotNull(result, "Search result should not be null")
        assertTrue(result.isNotBlank(), "Search result should not be blank")
    }
}
