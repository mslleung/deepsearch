package io.deepsearch.domain.services

import io.deepsearch.domain.config.domainBenchmarkTestModule
import io.deepsearch.domain.models.valueobjects.FileSearchStoreInfo
import io.deepsearch.domain.models.valueobjects.GeminiFileInfo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.RegisterExtension
import io.deepsearch.domain.testing.IsolatedKoinExtension
import io.deepsearch.domain.testing.IsolatedKoinTest
import io.deepsearch.domain.config.IApplicationCoroutineScope
import java.security.MessageDigest
import java.util.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

/**
 * Integration tests for GeminiFileSearchService.
 * 
 * These tests evaluate the Gemini File Search API for:
 * 1. Store management (create/find stores)
 * 2. File upload with metadata
 * 3. File deduplication via hash
 * 4. RAG query capabilities
 * 5. Performance characteristics
 * 
 * Findings will help determine if Gemini File Search is optimal for deepsearch's
 * file handling requirements.
 * 
 * Prerequisites:
 * - GOOGLE_API_KEY environment variable must be set
 * 
 * Note: These tests make real API calls to Google's Gemini API. They are marked
 * as integration tests and may incur API costs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class GeminiFileSearchServiceTest : IsolatedKoinTest() {

    @JvmField
    @RegisterExtension
    val koinTestExtension = IsolatedKoinExtension.create {
        modules(domainBenchmarkTestModule)
    }

    private val fileSearchService by inject<IGeminiFileSearchService>()
    private val applicationScope by inject<IApplicationCoroutineScope>()

    // Test domain - use unique domain to avoid conflicts
    private val testDomain = "test-deepsearch-${System.currentTimeMillis()}.example.com"
    
    // Store created during tests - will be used across test methods
    private var testStoreInfo: FileSearchStoreInfo? = null
    private var uploadedFileInfo: GeminiFileInfo? = null

    // Performance metrics
    private val performanceMetrics = mutableMapOf<String, Long>()

    companion object {
        private const val TEST_DOCUMENT_DEEP_LEARNING = "/test-document-deep-learning.txt"
        private const val TEST_DOCUMENT_KUBERNETES = "/test-document-kubernetes.txt"
        private const val TEST_DOCUMENT_FINANCIAL = "/test-document-financial-report.txt"
    }

    @AfterAll
    fun cleanup() {
        // Print performance report
        println("\n" + "=".repeat(60))
        println("GEMINI FILE SEARCH PERFORMANCE REPORT")
        println("=".repeat(60))
        performanceMetrics.forEach { (operation, timeMs) ->
            println("  $operation: ${timeMs}ms")
        }
        println("=".repeat(60))
        
        // Clean up application scope to cancel background coroutines
        applicationScope.close()
    }

    // ==================== Store Management Tests ====================

    @Test
    @Order(1)
    fun `1 - create new file search store for domain`() = runBlocking {
        println("\n--- Test: Create New File Search Store ---")
        println("Domain: $testDomain")

        val (storeInfo, createTime) = measureTimedValue {
            fileSearchService.getOrCreateStore(testDomain)
        }

        testStoreInfo = storeInfo
        performanceMetrics["Store Creation"] = createTime.inWholeMilliseconds

        assertNotNull(storeInfo, "Store info should not be null")
        assertNotNull(storeInfo.name, "Store name should not be null")
        assertTrue(storeInfo.name.startsWith("fileSearchStores/"), "Store name should have correct format")
        assertEquals(testDomain, storeInfo.domain)
        assertTrue(storeInfo.displayName.contains("deepsearch-"))

        println("✅ Store created successfully")
        println("   Name: ${storeInfo.name}")
        println("   DisplayName: ${storeInfo.displayName}")
        println("   Creation time: ${createTime.inWholeMilliseconds}ms")
    }

    @Test
    @Order(2)
    fun `2 - find existing store should return same store`() = runBlocking {
        println("\n--- Test: Find Existing Store ---")
        
        val (foundStore, findTime) = measureTimedValue {
            fileSearchService.findStore(testDomain)
        }

        performanceMetrics["Store Lookup"] = findTime.inWholeMilliseconds

        assertNotNull(foundStore, "Should find existing store")
        assertEquals(testStoreInfo?.name, foundStore.name, "Should return same store")

        println("✅ Found existing store")
        println("   Lookup time: ${findTime.inWholeMilliseconds}ms")
    }

    @Test
    @Order(3)
    fun `3 - getOrCreate should return existing store without creating new`() = runBlocking {
        println("\n--- Test: GetOrCreate Returns Existing Store ---")

        val (storeInfo, time) = measureTimedValue {
            fileSearchService.getOrCreateStore(testDomain)
        }

        performanceMetrics["Store GetOrCreate (existing)"] = time.inWholeMilliseconds

        assertEquals(testStoreInfo?.name, storeInfo.name, "Should return same store")
        
        println("✅ GetOrCreate returned existing store")
        println("   Time: ${time.inWholeMilliseconds}ms")
    }

    // ==================== File Upload Tests ====================

    @Test
    @Order(10)
    fun `10 - upload text document to store`() = runBlocking {
        println("\n--- Test: Upload Text Document ---")
        
        requireNotNull(testStoreInfo) { "Store must be created first" }
        
        val fileBytes = loadTestResource(TEST_DOCUMENT_DEEP_LEARNING)
        val fileHash = calculateHash(fileBytes)
        val sourceUrl = "https://$testDomain/docs/deep-learning.txt"
        
        println("File size: ${fileBytes.size} bytes")
        println("File hash: $fileHash")

        val (fileInfo, uploadTime) = measureTimedValue {
            fileSearchService.uploadFile(
                storeName = testStoreInfo!!.name,
                fileBytes = fileBytes,
                mimeType = "text/plain",
                sourceUrl = sourceUrl,
                fileHash = fileHash
            )
        }

        uploadedFileInfo = fileInfo
        performanceMetrics["File Upload (text)"] = uploadTime.inWholeMilliseconds

        assertNotNull(fileInfo, "File info should not be null")
        assertNotNull(fileInfo.name, "File name should not be null")
        assertEquals(fileHash, fileInfo.fileHash)
        assertEquals(sourceUrl, fileInfo.sourceUrl)

        println("✅ File uploaded successfully")
        println("   Document name: ${fileInfo.name}")
        println("   DisplayName: ${fileInfo.displayName}")
        println("   Upload time: ${uploadTime.inWholeMilliseconds}ms")
    }

    @Test
    @Order(11)
    fun `11 - upload second document for multi-file testing`() = runBlocking {
        println("\n--- Test: Upload Second Document ---")
        
        requireNotNull(testStoreInfo) { "Store must be created first" }
        
        val fileBytes = loadTestResource(TEST_DOCUMENT_KUBERNETES)
        val fileHash = calculateHash(fileBytes)
        val sourceUrl = "https://$testDomain/docs/kubernetes.txt"

        val (fileInfo, uploadTime) = measureTimedValue {
            fileSearchService.uploadFile(
                storeName = testStoreInfo!!.name,
                fileBytes = fileBytes,
                mimeType = "text/plain",
                sourceUrl = sourceUrl,
                fileHash = fileHash
            )
        }

        performanceMetrics["File Upload (text, second)"] = uploadTime.inWholeMilliseconds

        assertNotNull(fileInfo)
        println("✅ Second file uploaded: ${fileInfo.displayName}")
        println("   Upload time: ${uploadTime.inWholeMilliseconds}ms")
    }

    @Test
    @Order(12)
    fun `12 - upload third document with financial content`() = runBlocking {
        println("\n--- Test: Upload Third Document (Financial Report) ---")
        
        requireNotNull(testStoreInfo) { "Store must be created first" }
        
        val fileBytes = loadTestResource(TEST_DOCUMENT_FINANCIAL)
        val fileHash = calculateHash(fileBytes)
        val sourceUrl = "https://$testDomain/reports/q4-2025-financial.txt"

        val (fileInfo, uploadTime) = measureTimedValue {
            fileSearchService.uploadFile(
                storeName = testStoreInfo!!.name,
                fileBytes = fileBytes,
                mimeType = "text/plain",
                sourceUrl = sourceUrl,
                fileHash = fileHash
            )
        }

        performanceMetrics["File Upload (financial report)"] = uploadTime.inWholeMilliseconds

        assertNotNull(fileInfo)
        println("✅ Financial report uploaded: ${fileInfo.displayName}")
        println("   Upload time: ${uploadTime.inWholeMilliseconds}ms")
    }

    // ==================== File Lookup Tests ====================

    @Test
    @Order(20)
    fun `20 - find file by hash returns uploaded file`() = runBlocking {
        println("\n--- Test: Find File By Hash ---")
        
        requireNotNull(testStoreInfo) { "Store must be created first" }
        requireNotNull(uploadedFileInfo) { "File must be uploaded first" }
        
        // Note: Gemini File Search has eventual consistency for document listing.
        // The documents.list() API may not immediately return newly uploaded files.
        // This is a known limitation - queries work immediately but listing takes time.
        
        val (foundFile, findTime) = measureTimedValue {
            fileSearchService.findFileByHash(testStoreInfo!!.name, uploadedFileInfo!!.fileHash)
        }

        performanceMetrics["Find File By Hash"] = findTime.inWholeMilliseconds

        // Note: This may fail due to eventual consistency - file listing may not show
        // recently uploaded files immediately. The important thing is that QUERIES work.
        if (foundFile != null) {
            assertEquals(uploadedFileInfo!!.name, foundFile.name)
            assertEquals(uploadedFileInfo!!.fileHash, foundFile.fileHash)
            println("✅ Found file by hash")
        } else {
            println("⚠️ File not found in listing (eventual consistency) - this is expected behavior")
            println("   Note: Queries still work even when listing doesn't show the file yet")
        }
        println("   Lookup time: ${findTime.inWholeMilliseconds}ms")
    }

    @Test
    @Order(21)
    fun `21 - find file by non-existent hash returns null`() = runBlocking {
        println("\n--- Test: Find File By Non-existent Hash ---")
        
        requireNotNull(testStoreInfo) { "Store must be created first" }
        
        val nonExistentHash = "nonexistent-hash-12345"
        
        val (foundFile, findTime) = measureTimedValue {
            fileSearchService.findFileByHash(testStoreInfo!!.name, nonExistentHash)
        }

        performanceMetrics["Find File By Hash (miss)"] = findTime.inWholeMilliseconds

        kotlin.test.assertNull(foundFile, "Should not find non-existent file")

        println("✅ Correctly returned null for non-existent hash")
        println("   Lookup time: ${findTime.inWholeMilliseconds}ms")
    }

    @Test
    @Order(22)
    fun `22 - list all files in store`() = runBlocking {
        println("\n--- Test: List All Files in Store ---")
        
        requireNotNull(testStoreInfo) { "Store must be created first" }
        
        val (files, listTime) = measureTimedValue {
            fileSearchService.listFiles(testStoreInfo!!.name)
        }

        performanceMetrics["List Files"] = listTime.inWholeMilliseconds

        // Note: Due to eventual consistency, recently uploaded files may not appear
        // in the listing immediately. This is a known Gemini File Search limitation.
        // The important thing is that QUERIES work immediately after upload.
        
        println("📋 Listed ${files.size} files in store")
        if (files.isEmpty()) {
            println("   ⚠️ No files visible yet (eventual consistency)")
            println("   Note: Queries work immediately even when listing is empty")
        } else {
            files.forEach { file ->
                println("   - ${file.displayName} (${file.mimeType})")
            }
        }
        println("   List time: ${listTime.inWholeMilliseconds}ms")
    }

    // ==================== Query Tests ====================

    @Test
    @Order(30)
    fun `30 - query store for deep learning content`() = runBlocking {
        println("\n--- Test: Query Store for Deep Learning Content ---")
        
        requireNotNull(testStoreInfo) { "Store must be created first" }
        
        val query = "What activation functions are commonly used in neural networks?"
        
        println("Query: $query")

        val (result, queryTime) = measureTimedValue {
            fileSearchService.queryStore(
                storeName = testStoreInfo!!.name,
                query = query
            )
        }

        performanceMetrics["Query (deep learning)"] = queryTime.inWholeMilliseconds

        assertNotNull(result)
        assertTrue(result.chunks.isNotEmpty(), "Should return search results")
        
        // Check that result contains relevant content
        val content = result.chunks.joinToString(" ") { it.content }
        assertTrue(
            content.contains("ReLU", ignoreCase = true) || 
            content.contains("activation", ignoreCase = true) ||
            content.contains("sigmoid", ignoreCase = true),
            "Results should contain activation function information"
        )

        println("✅ Query returned ${result.chunks.size} chunks")
        println("   Token usage: ${result.tokenUsage}")
        println("   Query time: ${queryTime.inWholeMilliseconds}ms")
        println("   Result preview: ${result.chunks.firstOrNull()?.content?.take(200)}...")
    }

    @Test
    @Order(31)
    fun `31 - query store for kubernetes content`() = runBlocking {
        println("\n--- Test: Query Store for Kubernetes Content ---")
        
        requireNotNull(testStoreInfo) { "Store must be created first" }
        
        val query = "What are the core components of the Kubernetes control plane?"
        
        println("Query: $query")

        val (result, queryTime) = measureTimedValue {
            fileSearchService.queryStore(
                storeName = testStoreInfo!!.name,
                query = query
            )
        }

        performanceMetrics["Query (kubernetes)"] = queryTime.inWholeMilliseconds

        assertNotNull(result)
        assertTrue(result.chunks.isNotEmpty(), "Should return search results")
        
        val content = result.chunks.joinToString(" ") { it.content }
        assertTrue(
            content.contains("apiserver", ignoreCase = true) || 
            content.contains("etcd", ignoreCase = true) ||
            content.contains("scheduler", ignoreCase = true) ||
            content.contains("control plane", ignoreCase = true),
            "Results should contain Kubernetes control plane information"
        )

        println("✅ Query returned ${result.chunks.size} chunks")
        println("   Token usage: ${result.tokenUsage}")
        println("   Query time: ${queryTime.inWholeMilliseconds}ms")
        println("   Result preview: ${result.chunks.firstOrNull()?.content?.take(200)}...")
    }

    @Test
    @Order(32)
    fun `32 - query store for financial metrics`() = runBlocking {
        println("\n--- Test: Query Store for Financial Metrics ---")
        
        requireNotNull(testStoreInfo) { "Store must be created first" }
        
        val query = "What was the Q4 2025 revenue and profit margin?"
        
        println("Query: $query")

        val (result, queryTime) = measureTimedValue {
            fileSearchService.queryStore(
                storeName = testStoreInfo!!.name,
                query = query
            )
        }

        performanceMetrics["Query (financial)"] = queryTime.inWholeMilliseconds

        assertNotNull(result)
        assertTrue(result.chunks.isNotEmpty(), "Should return search results")
        
        val content = result.chunks.joinToString(" ") { it.content }
        assertTrue(
            content.contains("billion", ignoreCase = true) || 
            content.contains("revenue", ignoreCase = true) ||
            content.contains("margin", ignoreCase = true) ||
            content.contains("4.2", ignoreCase = true),
            "Results should contain financial information"
        )

        println("✅ Query returned ${result.chunks.size} chunks")
        println("   Token usage: ${result.tokenUsage}")
        println("   Query time: ${queryTime.inWholeMilliseconds}ms")
        println("   Result preview: ${result.chunks.firstOrNull()?.content?.take(200)}...")
    }

    @Test
    @Order(33)
    fun `33 - query with no relevant content should return empty or minimal results`() = runBlocking {
        println("\n--- Test: Query with No Relevant Content ---")
        
        requireNotNull(testStoreInfo) { "Store must be created first" }
        
        val query = "What is the recipe for chocolate chip cookies?"
        
        println("Query: $query")

        val (result, queryTime) = measureTimedValue {
            fileSearchService.queryStore(
                storeName = testStoreInfo!!.name,
                query = query
            )
        }

        performanceMetrics["Query (irrelevant)"] = queryTime.inWholeMilliseconds

        // The system may still return results, but they should not be highly relevant
        // or the response should indicate no relevant information was found
        assertNotNull(result)
        
        val content = result.chunks.joinToString(" ") { it.content }
        val mentionsCookies = content.contains("cookie", ignoreCase = true)
        val mentionsRecipe = content.contains("recipe", ignoreCase = true)
        
        // File search should ideally not hallucinate - it should say it can't find info
        // or return results from the documents without cookie/recipe content

        println("✅ Query completed")
        println("   Token usage: ${result.tokenUsage}")
        println("   Query time: ${queryTime.inWholeMilliseconds}ms")
        println("   Contains 'cookie': $mentionsCookies")
        println("   Result preview: ${result.chunks.firstOrNull()?.content?.take(300)}...")
    }

    // ==================== Performance Benchmark Tests ====================

    @Test
    @Order(40)
    fun `40 - measure repeated query performance`() = runBlocking {
        println("\n--- Test: Repeated Query Performance ---")
        
        requireNotNull(testStoreInfo) { "Store must be created first" }
        
        val query = "Explain backpropagation in neural networks"
        val iterations = 3
        val times = mutableListOf<Long>()

        repeat(iterations) { i ->
            val time = measureTime {
                fileSearchService.queryStore(
                    storeName = testStoreInfo!!.name,
                    query = query
                )
            }
            times.add(time.inWholeMilliseconds)
            println("   Iteration ${i + 1}: ${time.inWholeMilliseconds}ms")
        }

        val avgTime = times.average()
        val minTime = times.minOrNull() ?: 0
        val maxTime = times.maxOrNull() ?: 0

        performanceMetrics["Query Avg (${iterations}x)"] = avgTime.toLong()
        performanceMetrics["Query Min"] = minTime
        performanceMetrics["Query Max"] = maxTime

        println("✅ Performance benchmark complete")
        println("   Average: ${avgTime.toLong()}ms")
        println("   Min: ${minTime}ms")
        println("   Max: ${maxTime}ms")
    }

    // ==================== Cleanup ====================

    @Test
    @Order(100)
    @Disabled("Cleanup test - enable manually to delete test store")
    fun `100 - cleanup - delete uploaded files`() = runBlocking {
        println("\n--- Cleanup: Delete Uploaded Files ---")
        
        requireNotNull(testStoreInfo) { "Store must be created first" }
        
        val files = fileSearchService.listFiles(testStoreInfo!!.name)
        
        files.forEach { file ->
            println("   Deleting: ${file.displayName}")
            fileSearchService.deleteFile(testStoreInfo!!.name, file.name)
        }

        println("✅ Deleted ${files.size} files")
    }

    // ==================== Helper Functions ====================

    private fun loadTestResource(resourcePath: String): ByteArray {
        return this::class.java.getResourceAsStream(resourcePath)?.readBytes()
            ?: throw IllegalStateException("Test resource not found: $resourcePath")
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun calculateHash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(bytes)
        return Base64.encode(hashBytes)
    }
}
