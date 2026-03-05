package io.deepsearch.domain.services

import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.LinkSource
import io.deepsearch.domain.models.valueobjects.SearchQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import io.deepsearch.domain.testing.IsolatedKoinExtension
import io.deepsearch.domain.testing.IsolatedKoinTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SerperServiceTest : IsolatedKoinTest() {

    @JvmField
    @RegisterExtension
    val koinTestExtension = IsolatedKoinExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val serperService by inject<ISerperService>()
    private val normalizeUrlService by inject<INormalizeUrlService>()

    @ParameterizedTest
    @CsvSource(
        "pricing information, https://www.egltours.com/",
//        "company overview, https://www.egltours.com/",
//        "contact details, https://www.egltours.com/"
    )
    fun `discovers relevant links from site using SERP API`(query: String, url: String) = runTest(testCoroutineDispatcher) {
        // Given
        val searchQuery = SearchQuery(
            rawQuery = query,
            url = url
        )

        // When
        val links = serperService.searchLinks(searchQuery)

        // Then
        assertTrue(links.isNotEmpty(), "Should discover links via SERP search")
        
        // Normalize the target URL for comparison
        val normalizedTargetUrl = normalizeUrlService.normalize(searchQuery.url)
        links.forEach { link ->
            assertEquals(LinkSource.SERPER_SEARCH, link.source)
            assertTrue(link.url.startsWith(normalizedTargetUrl!!), "Link should be from the target site: ${link.url}")
            assertTrue(link.reason.isNotBlank(), "Should provide reason for link inclusion")
        }
    }
}

