package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.entities.WebpageMarkdown
import io.deepsearch.domain.repositories.IWebpageMarkdownRepository
import io.deepsearch.domain.services.ITextEmbeddingService
import io.deepsearch.infrastructure.config.infrastructureTestModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.getValue
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class ExposedWebpageMarkdownRepositoryTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinExtension = KoinTestExtension.create {
        modules(domainTestModule, infrastructureTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val webpageMarkdownRepository by inject<IWebpageMarkdownRepository>()
    private val textEmbeddingService by inject<ITextEmbeddingService>()

    @Test
    fun searchHybridWithLongQuery() = runTest(testCoroutineDispatcher) {
        // Create test data with markdown content
        val urlPrefix = "https://example.com/long-query/"
        
        val markdown = "Kotlin is a modern programming language that makes developers happier. It is concise, safe, and interoperable with Java."
        
        // Generate real embedding for the document
        val embeddingResult = textEmbeddingService.embedDocuments(listOf(markdown))
        val embedding = embeddingResult.embeddings[0]
        
        // Insert test webpage with searchable content and real embedding
        val webpage = WebpageMarkdown(
            url = "${urlPrefix}kotlin-guide",
            title = null,
            description = null,
            markdown = markdown,
            cleanedLinkRelevanceHtml = "<a href=\"/kotlin\">Kotlin guide</a>",
            httpStatus = 200,
            httpReason = "OK",
            mimeType = "text/html",
            embedding = embedding,
            createdAt = kotlin.time.Clock.System.now(),
            updatedAt = kotlin.time.Clock.System.now()
        )
        
        webpageMarkdownRepository.upsert(webpage)
        
        // Generate a very long query (over MAX_SEARCH_QUERY_LENGTH)
        val longQuery = "Kotlin programming language ".repeat(100) // Creates a ~2700 character query
        
        // Generate real embedding for the search query
        val queryEmbeddingResult = textEmbeddingService.embedQuery("Kotlin programming language")
        
        // Perform hybrid search with very long query - should not throw IndexOutOfBoundsException
        val searchResults = webpageMarkdownRepository.searchHybrid(
            textQuery = longQuery,
            queryEmbedding = queryEmbeddingResult.embedding,
            urlPrefix = urlPrefix,
            minUpdatedAtEpochMs = null,
            limit = 10
        )
        
        // Assert the query was handled successfully (even if truncated)
        assertNotNull(searchResults, "Search results should not be null even with long query")
        assertTrue(
            searchResults.any { it.url == "${urlPrefix}kotlin-guide" },
            "Search results should contain the Kotlin guide page even with truncated query"
        )
    }

    @Test
    fun searchHybrid() = runTest(testCoroutineDispatcher) {
        // Create test data with markdown content
        val urlPrefix = "https://example.com/"
        
        val markdown1 = "Kotlin is a modern programming language that makes developers happier. It is concise, safe, and interoperable with Java."
        val markdown2 = "Java is a popular programming language used for building enterprise applications. It has been around since 1995."
        val markdown3 = "Rust is a systems programming language focused on safety, speed, and concurrency without garbage collection."
        
        // Generate real embeddings for the documents
        val embeddingResult = textEmbeddingService.embedDocuments(listOf(markdown1, markdown2, markdown3))
        val embeddings = embeddingResult.embeddings
        
        // Insert test webpages with searchable content and real embeddings
        val webpage1 = WebpageMarkdown(
            url = "${urlPrefix}kotlin-guide",
            title = null,
            description = null,
            markdown = markdown1,
            cleanedLinkRelevanceHtml = "<a href=\"/kotlin\">Kotlin guide</a>",
            httpStatus = 200,
            httpReason = "OK",
            mimeType = "text/html",
            embedding = embeddings[0],
            createdAt = kotlin.time.Clock.System.now(),
            updatedAt = kotlin.time.Clock.System.now()
        )
        
        val webpage2 = WebpageMarkdown(
            url = "${urlPrefix}java-tutorial",
            title = null,
            description = null,
            markdown = markdown2,
            cleanedLinkRelevanceHtml = "<a href=\"/java\">Java tutorial</a>",
            httpStatus = 200,
            httpReason = "OK",
            mimeType = "text/html",
            embedding = embeddings[1],
            createdAt = kotlin.time.Clock.System.now(),
            updatedAt = kotlin.time.Clock.System.now()
        )
        
        val webpage3 = WebpageMarkdown(
            url = "${urlPrefix}rust-intro",
            title = null,
            description = null,
            markdown = markdown3,
            cleanedLinkRelevanceHtml = "<a href=\"/rust\">Rust intro</a>",
            httpStatus = 200,
            httpReason = "OK",
            mimeType = "text/html",
            embedding = embeddings[2],
            createdAt = kotlin.time.Clock.System.now(),
            updatedAt = kotlin.time.Clock.System.now()
        )
        
        // Insert all webpages
        webpageMarkdownRepository.upsert(webpage1)
        webpageMarkdownRepository.upsert(webpage2)
        webpageMarkdownRepository.upsert(webpage3)
        
        // Generate real embedding for the search query
        val queryEmbeddingResult = textEmbeddingService.embedQuery("Kotlin programming language")
        
        // Perform hybrid search for "Kotlin"
        val searchResults = webpageMarkdownRepository.searchHybrid(
            textQuery = "Kotlin",
            queryEmbedding = queryEmbeddingResult.embedding,
            urlPrefix = urlPrefix,
            minUpdatedAtEpochMs = null,
            limit = 10
        )
        
        // Assert results
        assertTrue(searchResults.isNotEmpty(), "Search results should not be empty")
        assertTrue(
            searchResults.any { it.url == "${urlPrefix}kotlin-guide" },
            "Search results should contain the Kotlin guide page"
        )
    }

}

/**
 * Unit tests for ExposedWebpageMarkdownRepository utility functions.
 * These don't require Koin setup as they're testing pure functions.
 */
class ExposedWebpageMarkdownRepositoryUnitTest {
    
    @Test
    fun `sanitizes normal query correctly`() {
        val query = "Kotlin programming language"
        val sanitized = ExposedWebpageMarkdownRepository.sanitizeSearchQuery(query)
        assertEquals("Kotlin programming language", sanitized)
    }
    
    @Test
    fun `truncates very long query`() {
        val longQuery = "a".repeat(1000)
        val sanitized = ExposedWebpageMarkdownRepository.sanitizeSearchQuery(longQuery)
        assertTrue(sanitized.length <= 500, "Query should be truncated to max 500 characters")
        assertEquals(500, sanitized.length)
    }
    
    @Test
    fun `removes emoji from query`() {
        val queryWithEmoji = "Kotlin 😀 programming 🎉 language"
        val sanitized = ExposedWebpageMarkdownRepository.sanitizeSearchQuery(queryWithEmoji)
        assertFalse(sanitized.contains("😀"))
        assertFalse(sanitized.contains("🎉"))
        assertTrue(sanitized.contains("Kotlin"))
        assertTrue(sanitized.contains("programming"))
    }
    
    @Test
    fun `collapses multiple spaces`() {
        val queryWithSpaces = "Kotlin    programming     language"
        val sanitized = ExposedWebpageMarkdownRepository.sanitizeSearchQuery(queryWithSpaces)
        assertEquals("Kotlin programming language", sanitized)
    }
    
    @Test
    fun `trims whitespace`() {
        val queryWithWhitespace = "  Kotlin programming language  "
        val sanitized = ExposedWebpageMarkdownRepository.sanitizeSearchQuery(queryWithWhitespace)
        assertEquals("Kotlin programming language", sanitized)
    }
    
    @Test
    fun `handles empty query`() {
        val emptyQuery = ""
        val sanitized = ExposedWebpageMarkdownRepository.sanitizeSearchQuery(emptyQuery)
        assertEquals("", sanitized)
    }
    
    @Test
    fun `handles query with only whitespace`() {
        val whitespaceQuery = "   \t  \n  "
        val sanitized = ExposedWebpageMarkdownRepository.sanitizeSearchQuery(whitespaceQuery)
        assertEquals("", sanitized)
    }
    
    @Test
    fun `removes control characters`() {
        val queryWithControlChars = "Kotlin\u0000programming\u0001language"
        val sanitized = ExposedWebpageMarkdownRepository.sanitizeSearchQuery(queryWithControlChars)
        assertEquals("Kotlinprogramminglanguage", sanitized)
    }
}