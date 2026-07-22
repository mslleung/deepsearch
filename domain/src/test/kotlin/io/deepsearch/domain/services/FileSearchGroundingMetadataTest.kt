package io.deepsearch.domain.services

import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.FileSearch
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Tool
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.config.domainBenchmarkTestModule
import io.deepsearch.domain.models.valueobjects.FileSearchStoreInfo
import io.deepsearch.domain.testing.IsolatedKoinExtension
import io.deepsearch.domain.testing.IsolatedKoinTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.RegisterExtension
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

/**
 * Diagnostic test to inspect what Gemini File Search returns in grounding metadata.
 *
 * This test uploads a file with a known sourceUrl as custom metadata, then queries
 * the store using a raw generateContent call to inspect every field of the
 * GroundingChunkRetrievedContext: uri, title, text, documentName.
 *
 * The goal is to determine whether the grounding metadata contains a reference
 * back to the original document so we can resolve its custom metadata (sourceUrl).
 *
 * Prerequisites:
 * - GOOGLE_API_KEY environment variable must be set
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class FileSearchGroundingMetadataTest : IsolatedKoinTest() {

    @JvmField
    @RegisterExtension
    val koinTestExtension = IsolatedKoinExtension.create {
        modules(domainBenchmarkTestModule)
    }

    private val fileSearchService by inject<IGeminiFileSearchService>()
    private val client by inject<Client>()
    private val applicationScope by inject<IApplicationCoroutineScope>()

    private val testDomain = "grounding-test-${System.currentTimeMillis()}.example.com"
    private val knownSourceUrl = "https://www.example.com/docs/grounding-test-article.html"

    private var testStoreInfo: FileSearchStoreInfo? = null

    companion object {
        private const val TEST_CONTENT = """
            # Quantum Computing Fundamentals
            
            Quantum computing leverages quantum mechanical phenomena such as superposition and
            entanglement to perform computations. Unlike classical bits which are either 0 or 1,
            quantum bits (qubits) can exist in a superposition of both states simultaneously.
            
            ## Key Concepts
            
            ### Superposition
            A qubit can be in state |0⟩, |1⟩, or any quantum superposition of these states.
            This is described by α|0⟩ + β|1⟩ where |α|² + |β|² = 1.
            
            ### Entanglement
            When qubits become entangled, the quantum state of one qubit cannot be described
            independently of the others. Measuring one instantly determines the state of its
            entangled partner, regardless of distance.
            
            ### Quantum Gates
            Quantum gates manipulate qubits through unitary transformations. Common gates include:
            - Hadamard gate (H): Creates superposition from a basis state
            - CNOT gate: A two-qubit gate that flips the target if the control is |1⟩
            - Pauli gates (X, Y, Z): Single-qubit rotations
            
            ## Applications
            Quantum computers excel at problems like integer factorization (Shor's algorithm),
            database search (Grover's algorithm), and quantum simulation of molecular structures.
            """
    }

    @AfterAll
    fun cleanup() {
        try {
            applicationScope.close()
        } catch (_: Exception) {
        }
    }

    @Test
    @Order(1)
    fun `1 - create store and upload test document`() = runBlocking {
        println("\n${"=".repeat(70)}")
        println("GROUNDING METADATA EXPERIMENT - Step 1: Setup")
        println("=".repeat(70))

        val storeInfo = fileSearchService.getOrCreateStore(testDomain)
        testStoreInfo = storeInfo
        println("Store created: ${storeInfo.name}")

        val fileBytes = TEST_CONTENT.trimIndent().toByteArray()
        val fileHash = calculateHash(fileBytes)

        println("Uploading file with sourceUrl: $knownSourceUrl")

        val (fileInfo, uploadTime) = measureTimedValue {
            fileSearchService.uploadFile(
                storeName = storeInfo.name,
                fileBytes = fileBytes,
                mimeType = "text/plain",
                sourceUrl = knownSourceUrl,
                fileHash = fileHash
            )
        }

        println("Upload complete in ${uploadTime.inWholeMilliseconds}ms")
        println("Document resource name: ${fileInfo.name}")
        println("DisplayName: ${fileInfo.displayName}")
        println("Stored sourceUrl: ${fileInfo.sourceUrl}")
    }

    @Test
    @Order(2)
    fun `2 - raw generateContent call and inspect grounding metadata`() = runBlocking {
        println("\n${"=".repeat(70)}")
        println("GROUNDING METADATA EXPERIMENT - Step 2: Raw API Inspection")
        println("=".repeat(70))

        requireNotNull(testStoreInfo) { "Store must be created first (run test 1)" }

        val query = "What are the key concepts in quantum computing? Explain superposition and entanglement."

        val fileSearchTool = Tool.builder()
            .fileSearch(
                FileSearch.builder()
                    .fileSearchStoreNames(listOf(testStoreInfo!!.name))
                    .build()
            )
            .build()

        val modelId = ModelIds.GEMINI_3_5_FLASH_LITE.modelId
        println("Using model: $modelId")

        val response = client.models.generateContent(
            modelId,
            query,
            GenerateContentConfig.builder()
                .tools(listOf(fileSearchTool))
                .systemInstruction(
                    Content.fromParts(Part.fromText("Answer using only information from the file search results."))
                )
                .build()
        )

        println("\n--- Response Text (first 300 chars) ---")
        println(response.text()?.take(300) ?: "(no text)")

        println("\n--- Candidates Count ---")
        val candidates = response.candidates().orElse(listOf())
        println("Candidates: ${candidates.size}")

        val candidate = candidates.firstOrNull()
        if (candidate == null) {
            println("ERROR: No candidates in response")
            return@runBlocking
        }

        val groundingMetadata = candidate.groundingMetadata().orElse(null)
        if (groundingMetadata == null) {
            println("ERROR: No grounding metadata in response")
            return@runBlocking
        }

        println("\n--- Full Grounding Metadata toString ---")
        println(groundingMetadata.toString())

        // Retrieval queries
        println("\n--- Retrieval Queries ---")
        val retrievalQueries = groundingMetadata.retrievalQueries().orElse(listOf())
        if (retrievalQueries.isEmpty()) {
            println("(none)")
        } else {
            retrievalQueries.forEachIndexed { i, q -> println("  [$i] $q") }
        }

        // Grounding chunks
        println("\n--- Grounding Chunks ---")
        val groundingChunks = groundingMetadata.groundingChunks().orElse(listOf())
        println("Total chunks: ${groundingChunks.size}")

        groundingChunks.forEachIndexed { i, chunk ->
            println("\n  Chunk [$i]:")
            println("    chunk.toString() = ${chunk.toString().take(500)}")

            val rc = chunk.retrievedContext().orElse(null)
            if (rc != null) {
                println("    retrievedContext present: YES")
                println("    rc.uri()          = ${rc.uri().orElse("(empty)")}")
                println("    rc.title()        = ${rc.title().orElse("(empty)")}")
                println("    rc.text()         = ${rc.text().orElse("(empty)").take(200)}")
                println("    rc.documentName() = ${rc.documentName().orElse("(empty)")}")
                // Try ragChunk too
                val ragChunk = rc.ragChunk().orElse(null)
                println("    rc.ragChunk()     = ${ragChunk?.toString()?.take(200) ?: "(null)"}")
            } else {
                println("    retrievedContext present: NO")
            }

            val web = chunk.web().orElse(null)
            if (web != null) {
                println("    web present: YES")
                println("    web.uri()    = ${web.uri().orElse("(empty)")}")
                println("    web.title()  = ${web.title().orElse("(empty)")}")
            } else {
                println("    web present: NO")
            }
        }

        // Grounding supports
        println("\n--- Grounding Supports ---")
        val supports = groundingMetadata.groundingSupports().orElse(listOf())
        println("Total supports: ${supports.size}")

        supports.forEachIndexed { i, support ->
            println("\n  Support [$i]:")
            val segment = support.segment().orElse(null)
            if (segment != null) {
                println("    segment.text() = ${segment.text().orElse("(empty)").take(200)}")
            }
            val indices = support.groundingChunkIndices().orElse(listOf())
            println("    chunkIndices   = $indices")
            val scores = support.confidenceScores().orElse(listOf())
            println("    confidenceScores = $scores")
        }

        println("\n${"=".repeat(70)}")
        println("KEY FINDINGS:")
        println("=".repeat(70))
        println("Known sourceUrl at upload: $knownSourceUrl")
        if (groundingChunks.isNotEmpty()) {
            val firstRc = groundingChunks.first().retrievedContext().orElse(null)
            if (firstRc != null) {
                val uri = firstRc.uri().orElse("(empty)")
                val title = firstRc.title().orElse("(empty)")
                val docName = firstRc.documentName().orElse("(empty)")
                println("First chunk rc.uri()          = $uri")
                println("First chunk rc.title()        = $title")
                println("First chunk rc.documentName() = $docName")
                println()
                println("uri matches sourceUrl?        : ${uri == knownSourceUrl}")
                println("uri looks like resource name?  : ${uri.contains("fileSearchStores/") || uri.contains("documents/")}")
                println("documentName is populated?     : ${docName != "(empty)"}")
                println("title matches displayName?     : (displayName was last path segment of sourceUrl)")
            }
        }
        println("=".repeat(70))
    }

    @Test
    @Order(3)
    fun `3 - query via fileSearchService and inspect FileSearchChunk sourceUrl`() = runBlocking {
        println("\n${"=".repeat(70)}")
        println("GROUNDING METADATA EXPERIMENT - Step 3: Agent Path Inspection")
        println("=".repeat(70))

        requireNotNull(testStoreInfo) { "Store must be created first (run test 1)" }

        val query = "What is quantum entanglement?"

        val (result, queryTime) = measureTimedValue {
            fileSearchService.queryStore(
                storeName = testStoreInfo!!.name,
                query = query
            )
        }

        println("Query time: ${queryTime.inWholeMilliseconds}ms")
        println("Chunks returned: ${result.chunks.size}")
        println("Known sourceUrl: $knownSourceUrl")

        result.chunks.forEachIndexed { i, chunk ->
            println("\n  FileSearchChunk [$i]:")
            println("    sourceUrl      = '${chunk.sourceUrl}'")
            println("    fileName       = '${chunk.fileName}'")
            println("    content        = '${chunk.content.take(200)}'")
            println("    relevanceScore = ${chunk.relevanceScore}")
            println()
            println("    sourceUrl matches known? : ${chunk.sourceUrl == knownSourceUrl}")
            println("    sourceUrl is blank?      : ${chunk.sourceUrl.isBlank()}")
            println("    sourceUrl looks internal? : ${chunk.sourceUrl.contains("fileSearchStores/")}")
        }

        println("\n${"=".repeat(70)}")
    }

    @Test
    @Order(4)
    fun `4 - attempt document lookup via documents-get`() = runBlocking {
        println("\n${"=".repeat(70)}")
        println("GROUNDING METADATA EXPERIMENT - Step 4: Document Lookup")
        println("=".repeat(70))

        requireNotNull(testStoreInfo) { "Store must be created first (run test 1)" }

        // First, list documents to see what names look like
        val files = fileSearchService.listFiles(testStoreInfo!!.name)
        println("Documents in store: ${files.size}")
        files.forEach { file ->
            println("  name: ${file.name}")
            println("  displayName: ${file.displayName}")
            println("  sourceUrl (from custom metadata): ${file.sourceUrl}")
        }

        // Now attempt documents.get for each document
        if (files.isNotEmpty()) {
            val docName = files.first().name
            println("\nAttempting client.fileSearchStores.documents.get('$docName')...")
            try {
                val doc = client.fileSearchStores.documents.get(docName, null)
                println("SUCCESS - documents.get returned:")
                println("  name: ${doc.name().orElse("(empty)")}")
                println("  displayName: ${doc.displayName().orElse("(empty)")}")
                val metadata = doc.customMetadata().orElse(emptyList())
                println("  customMetadata count: ${metadata.size}")
                metadata.forEach { meta ->
                    val key = meta.key().orElse("?")
                    val value = meta.stringValue().orElse(meta.numericValue().map { it.toString() }.orElse("?"))
                    println("    $key = $value")
                }
            } catch (e: Exception) {
                println("FAILED - documents.get threw: ${e::class.simpleName}: ${e.message}")
            }
        }

        println("=".repeat(70))
    }

    @Test
    @Order(100)
    fun `100 - cleanup test store`() = runBlocking {
        println("\n--- Cleanup ---")
        val store = testStoreInfo ?: return@runBlocking
        try {
            val files = fileSearchService.listFiles(store.name)
            files.forEach { file ->
                fileSearchService.deleteFile(store.name, file.name)
                println("Deleted file: ${file.displayName}")
            }
        } catch (e: Exception) {
            println("Cleanup warning: ${e.message}")
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun calculateHash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(bytes)
        return Base64.encode(hashBytes)
    }
}
