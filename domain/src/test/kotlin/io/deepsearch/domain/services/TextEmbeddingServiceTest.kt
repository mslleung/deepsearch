package io.deepsearch.domain.services

import io.deepsearch.domain.config.domainTestModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextEmbeddingServiceTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val textEmbeddingService by inject<ITextEmbeddingService>()

    @Test
    fun `embedDocuments generates embeddings with correct dimensions`() = runTest(testCoroutineDispatcher) {
        // Given
        val documents = listOf(
            "The cat is sleeping on the couch.",
            "Artificial intelligence and machine learning are transforming technology.",
            "The weather today is sunny and warm."
        )

        // When
        val embeddings = textEmbeddingService.embedDocuments(documents)

        // Then
        assertEquals(3, embeddings.size, "Should generate 3 embeddings")
        embeddings.forEach { embedding ->
            assertEquals(1536, embedding.size, "Each embedding should have 1536 dimensions")
            assertTrue(embedding.any { it != 0f }, "Embedding should contain non-zero values")
        }
    }

    @Test
    fun `embedQuery generates embedding with correct dimensions`() = runTest(testCoroutineDispatcher) {
        // Given
        val query = "What is machine learning?"

        // When
        val embedding = textEmbeddingService.embedQuery(query)

        // Then
        assertEquals(1536, embedding.size, "Embedding should have 1536 dimensions")
        assertTrue(embedding.any { it != 0f }, "Embedding should contain non-zero values")
    }

    @Test
    fun `embedDocuments handles empty list`() = runTest(testCoroutineDispatcher) {
        // Given
        val documents = emptyList<String>()

        // When
        val embeddings = textEmbeddingService.embedDocuments(documents)

        // Then
        assertEquals(0, embeddings.size, "Should return empty list for empty input")
    }

    @Test
    fun `similar texts have higher cosine similarity than dissimilar texts`() = runTest(testCoroutineDispatcher) {
        // Given
        val documents = listOf(
            "The cat is sleeping.",
            "The feline is napping.",
            "Python is a programming language."
        )

        // When
        val embeddings = textEmbeddingService.embedDocuments(documents)

        // Then
        val similarityCatFeline = cosineSimilarity(embeddings[0], embeddings[1])
        val similarityCatPython = cosineSimilarity(embeddings[0], embeddings[2])

        assertTrue(
            similarityCatFeline > similarityCatPython,
            "Similar texts (cat/feline) should have higher similarity ($similarityCatFeline) " +
                    "than dissimilar texts (cat/python) ($similarityCatPython)"
        )
    }

    @Test
    fun `embedQuery and embedDocuments generate different embeddings for same text`() = runTest(testCoroutineDispatcher) {
        // Given
        val text = "What is artificial intelligence?"

        // When
        val queryEmbedding = textEmbeddingService.embedQuery(text)
        val documentEmbeddings = textEmbeddingService.embedDocuments(listOf(text))
        val documentEmbedding = documentEmbeddings[0]

        // Then
        // They should be different because they use different task types
        // (RETRIEVAL_QUERY vs RETRIEVAL_DOCUMENT)
        val areDifferent = queryEmbedding.indices.any { i ->
            queryEmbedding[i] != documentEmbedding[i]
        }
        assertTrue(areDifferent, "Query and document embeddings should differ due to different task types")
    }

    /**
     * Calculate cosine similarity between two vectors.
     * Returns a value between -1 and 1, where 1 means identical direction.
     */
    private fun cosineSimilarity(vec1: List<Float>, vec2: List<Float>): Double {
        require(vec1.size == vec2.size) { "Vectors must have the same size" }

        var dotProduct = 0.0
        var magnitude1 = 0.0
        var magnitude2 = 0.0

        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
            magnitude1 += vec1[i] * vec1[i]
            magnitude2 += vec2[i] * vec2[i]
        }

        magnitude1 = sqrt(magnitude1)
        magnitude2 = sqrt(magnitude2)

        return if (magnitude1 == 0.0 || magnitude2 == 0.0) {
            0.0
        } else {
            dotProduct / (magnitude1 * magnitude2)
        }
    }
}

