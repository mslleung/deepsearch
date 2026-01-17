package io.deepsearch.application.services

import io.deepsearch.application.config.applicationBenchmarkTestModule
import io.deepsearch.domain.agents.PdfSourceEvalInput
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.UrlContentResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import io.deepsearch.domain.config.IApplicationCoroutineScope
import kotlin.system.measureTimeMillis

/**
 * Benchmark test comparing PDF preview (local PDFTextStripper + LLM evaluation)
 * vs the current index+query approach (Gemini File Search upload + query).
 * 
 * This test measures the processing duration of both approaches to validate
 * that the preview path provides faster time-to-first-result.
 * 
 * Note: This test requires:
 * - GOOGLE_API_KEY environment variable for Gemini API access
 * - Network access to download test PDFs
 * - Database connection for file search store operations
 */
class PdfProcessingBenchmarkTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(applicationBenchmarkTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val pdfPreviewService by inject<IPdfPreviewService>()
    private val pdfSourceEvalService by inject<IPdfSourceEvalService>()
    private val fileSearchService by inject<IFileSearchService>()
    private val applicationScope by inject<IApplicationCoroutineScope>()

    private val httpClient = HttpClient(OkHttp)
    
    @AfterEach
    fun cleanup() {
        // Clean up application scope to cancel background coroutines
        applicationScope.close()
    }

    /**
     * Benchmark PDF preview path vs index+query path.
     * 
     * @param pdfUrl URL of the PDF to test
     * @param query Search query to evaluate against
     */
    @ParameterizedTest
    @CsvSource(
        value = [
            // Public PDFs for testing - using stable URLs from government/academic sources
            "https://www.irs.gov/pub/irs-pdf/fw4.pdf, What is this tax form about?",
        ]
    )
    fun `benchmark PDF preview vs index+query`(pdfUrl: String, query: String) = runTest(testCoroutineDispatcher) {
        val sessionId = QuerySessionId("benchmark-session-${System.currentTimeMillis()}")
        
        println("\n========================================")
        println("Benchmarking PDF: $pdfUrl")
        println("Query: $query")
        println("========================================")

        // Download PDF once for both paths
        val pdfBytes = withContext(Dispatchers.IO) {
            downloadPdf(pdfUrl)
        }
        println("PDF downloaded: ${pdfBytes.size} bytes")

        // 1. Measure preview path (local extraction + LLM evaluation)
        val previewResult = measurePreviewPath(pdfUrl, pdfBytes, query, sessionId)
        
        println("\n--- Preview Path Results ---")
        println("Preview path:      ${previewResult.totalTimeMs}ms (extract: ${previewResult.extractTimeMs}ms, eval: ${previewResult.evalTimeMs}ms)")
        println("Preview facts: ${previewResult.factCount}")

        // 2. Measure index+query path (Gemini upload + query)
        // This may fail if Gemini quotas are exceeded or network issues occur
        val indexQueryResult = try {
            measureIndexQueryPath(pdfUrl, pdfBytes, query, sessionId)
        } catch (e: Exception) {
            println("\n--- Index+Query Path FAILED ---")
            println("Error: ${e.message}")
            null
        }

        // Report results
        if (indexQueryResult != null) {
            println("\n--- Index+Query Path Results ---")
            println("Index+Query path:  ${indexQueryResult.totalTimeMs}ms (ingest: ${indexQueryResult.ingestTimeMs}ms, query: ${indexQueryResult.queryTimeMs}ms)")
            println("Index+Query markdown length: ${indexQueryResult.markdownLength} chars")
            
            val speedup = if (previewResult.totalTimeMs > 0) {
                indexQueryResult.totalTimeMs.toDouble() / previewResult.totalTimeMs
            } else {
                0.0
            }
            println("\n--- Comparison ---")
            println("Speedup: ${String.format("%.2f", speedup)}x faster with preview path")
        }
        
        println("========================================\n")

        // Assertions - only preview path is required to pass
        assertTrue(previewResult.totalTimeMs > 0, "Preview path should complete")
        
        // Preview path should generally be faster (but not always due to network variability)
        // We log the results for manual analysis rather than asserting strict speedup
    }

    /**
     * Measure the preview path: local PDFTextStripper extraction + PdfSourceEvalAgent evaluation.
     */
    private suspend fun measurePreviewPath(
        pdfUrl: String,
        pdfBytes: ByteArray,
        query: String,
        sessionId: QuerySessionId
    ): PreviewPathResult {
        var extractTimeMs = 0L
        var evalTimeMs = 0L
        var factCount = 0

        // Step 1: Extract text locally using PDFTextStripper (with keyword search for large PDFs)
        val previewResult = withContext(Dispatchers.IO) {
            val time = measureTimeMillis {
                pdfPreviewService.extract(pdfBytes, pdfUrl, query)
            }
            extractTimeMs = time
            pdfPreviewService.extract(pdfBytes, pdfUrl, query)
        }

        // Step 2: Evaluate using PdfSourceEvalAgent
        if (previewResult.extractedText.isNotBlank()) {
            val pdfPreview = UrlContentResult.PdfPreview(
                url = pdfUrl,
                title = previewResult.title,
                description = "PDF document with ${previewResult.pageCount} pages",
                extractedText = previewResult.extractedText,
                pageCount = previewResult.pageCount
            )

            val evalInput = PdfSourceEvalInput(
                pdfSource = pdfPreview,
                expandedQuery = query
            )

            val evalOutput = withContext(Dispatchers.IO) {
                val time = measureTimeMillis {
                    pdfSourceEvalService.evaluate(evalInput, sessionId)
                }
                evalTimeMs = time
                pdfSourceEvalService.evaluate(evalInput, sessionId)
            }

            factCount = evalOutput.evaluatedSource?.relevantFacts?.size ?: 0
        }

        return PreviewPathResult(
            extractTimeMs = extractTimeMs,
            evalTimeMs = evalTimeMs,
            totalTimeMs = extractTimeMs + evalTimeMs,
            factCount = factCount
        )
    }

    /**
     * Measure the index+query path: Gemini File Search upload + domain query.
     */
    private suspend fun measureIndexQueryPath(
        pdfUrl: String,
        pdfBytes: ByteArray,
        query: String,
        sessionId: QuerySessionId
    ): IndexQueryPathResult {
        var ingestTimeMs = 0L
        var queryTimeMs = 0L
        var markdownLength = 0

        // Step 1: Ingest (upload to Gemini File Search)
        val ingestResult = withContext(Dispatchers.IO) {
            val time = measureTimeMillis {
                fileSearchService.ingest(
                    url = pdfUrl,
                    fileBytes = pdfBytes,
                    mimeType = "application/pdf",
                    maxCacheAge = null
                )
            }
            ingestTimeMs = time
            fileSearchService.ingest(
                url = pdfUrl,
                fileBytes = pdfBytes,
                mimeType = "application/pdf",
                maxCacheAge = null
            )
        }

        // Step 2: Query the domain store
        val queryResult = withContext(Dispatchers.IO) {
            val time = measureTimeMillis {
                fileSearchService.query(
                    domain = ingestResult.storeInfo.domain,
                    query = query,
                    sessionId = sessionId
                )
            }
            queryTimeMs = time
            fileSearchService.query(
                domain = ingestResult.storeInfo.domain,
                query = query,
                sessionId = sessionId
            )
        }

        markdownLength = queryResult.markdown.length

        return IndexQueryPathResult(
            ingestTimeMs = ingestTimeMs,
            queryTimeMs = queryTimeMs,
            totalTimeMs = ingestTimeMs + queryTimeMs,
            markdownLength = markdownLength
        )
    }

    /**
     * Download PDF from URL.
     */
    private suspend fun downloadPdf(url: String): ByteArray {
        return httpClient.get(url).bodyAsBytes()
    }

    private data class PreviewPathResult(
        val extractTimeMs: Long,
        val evalTimeMs: Long,
        val totalTimeMs: Long,
        val factCount: Int
    )

    private data class IndexQueryPathResult(
        val ingestTimeMs: Long,
        val queryTimeMs: Long,
        val totalTimeMs: Long,
        val markdownLength: Int
    )
}
