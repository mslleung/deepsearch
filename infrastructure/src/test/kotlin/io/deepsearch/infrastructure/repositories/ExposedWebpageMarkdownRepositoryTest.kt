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
            markdown = markdown1,
            html = "<html><body>Kotlin guide</body></html>",
            httpStatus = 200,
            httpReason = "OK",
            mimeType = "text/html",
            embedding = embeddings[0],
            createdAt = kotlin.time.Clock.System.now(),
            updatedAt = kotlin.time.Clock.System.now()
        )
        
        val webpage2 = WebpageMarkdown(
            url = "${urlPrefix}java-tutorial",
            markdown = markdown2,
            html = "<html><body>Java tutorial</body></html>",
            httpStatus = 200,
            httpReason = "OK",
            mimeType = "text/html",
            embedding = embeddings[1],
            createdAt = kotlin.time.Clock.System.now(),
            updatedAt = kotlin.time.Clock.System.now()
        )
        
        val webpage3 = WebpageMarkdown(
            url = "${urlPrefix}rust-intro",
            markdown = markdown3,
            html = "<html><body>Rust intro</body></html>",
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