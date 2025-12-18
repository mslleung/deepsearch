package io.deepsearch.application.services.batch

import io.deepsearch.application.config.applicationBenchmarkTestModule
import io.deepsearch.application.services.CachedWebpageResult
import io.deepsearch.application.services.IBatchPeriodicIndexJobService
import io.deepsearch.application.services.IWebpageCacheService
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJobState
import io.deepsearch.domain.models.valueobjects.UserId
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * Integration test for the batch periodic index pipeline.
 * 
 * Tests the complete 4-stage batch processing flow:
 * 1. CRAWL_AND_EXTRACT - Browser-based crawl + extraction
 * 2. CONTENT_LLM_BATCH - LLM batch for semantic/table/icon identification
 * 3. LLM_TABLE_INTERPRETATION - LLM batch for table interpretation
 * 4. FINALIZE_AND_CACHE_EMBEDDING - Finalize markdown and cache
 * 
 * Similar to WebpageExtractionServiceTest but tests the batch path instead of the interactive path.
 */
@OptIn(ExperimentalTime::class)
class BatchPipelineIntegrationTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        // Use benchmark module which provides real dispatchers (Dispatchers.IO, etc.)
        // instead of StandardTestDispatcher which skips all timing/delays
        modules(applicationBenchmarkTestModule)
    }

    private val batchJobService by inject<IBatchPeriodicIndexJobService>()
    private val webpageCacheService by inject<IWebpageCacheService>()

    /**
     * Note: This test uses applicationBenchmarkTestModule and runBlocking because:
     * - Standard Koin test modules use StandardTestDispatcher which skips all delays (virtual time)
     * - Batch jobs require real-time waiting as they execute actual API calls and processing
     * - applicationBenchmarkTestModule provides DefaultDispatcherProvider with real dispatchers
     * - Using runBlocking with real dispatchers allows the batch pipeline to execute properly
     */
    @ParameterizedTest
    @ValueSource(
        strings = [
//            "https://mybeame.com/beame-student-discount",
//            "https://www.otandp.com/body-check/",
//            "https://www.otandp.com/about/history",
//            "https://sleekflow.io/pricing",
            "https://sleekflow.io/fair-use-policy",
//            "https://sleekflow.io/ticketing"
        ]
    )
    @Timeout(value = 1, unit = TimeUnit.HOURS)
    fun `batch pipeline produces valid cached markdown`(url: String) = runBlocking {
        // 1. Start a batch job with maxUrlCount=1 (single page for focused testing)
        val job = batchJobService.start(
            baseUrl = url,
            maxUrlCount = 1,
            userId = UserId(1)
        )
        
        assertNotNull(job.id, "Job should have an ID after creation")
        assertEquals(BatchPeriodicIndexJobState.CRAWL_AND_EXTRACT, job.state, "Job should start in CRAWL_AND_EXTRACT state")
        
        // 2. Poll until job completes (with timeout)
        // Note: Batch jobs use Gemini Batch API which can take up to 24+ hours,
        // but for single-page tests, it should complete much faster
        val timeout = 60.minutes
        val pollInterval = 5.seconds
        val deadline = Clock.System.now() + timeout
        
        var completedJob = batchJobService.findById(job.id!!)
        while (completedJob?.isTerminal() != true && Clock.System.now() < deadline) {
            delay(pollInterval)
            completedJob = batchJobService.findById(job.id!!)
            
            // Log progress for debugging
            completedJob?.let { j ->
                println("Job ${j.id} state: ${j.state}, processed: ${j.urlsProcessed}, content: ${j.urlsContentProcessed}, final: ${j.urlsFinalProcessed}, cached: ${j.urlsCached}")
            }
        }
        
        // 3. Verify job completed successfully
        assertNotNull(completedJob, "Job should exist")
        assertEquals(
            BatchPeriodicIndexJobState.COMPLETED, 
            completedJob!!.state, 
            "Job should complete successfully. Error: ${completedJob.errorMessage ?: "none"}"
        )
        assertTrue(completedJob.urlsCached > 0, "At least one URL should be cached")
        
        // 4. Verify cached markdown exists and has meaningful content
        val cachedResult = webpageCacheService.getCachedMarkdown(url, null)
        assertTrue(cachedResult is CachedWebpageResult.Hit, "Cached result should be a hit for URL: $url")
        
        val webpageMarkdown = (cachedResult as CachedWebpageResult.Hit).webpageMarkdown
        assertNotNull(webpageMarkdown.markdown, "Markdown should not be null")
        assertTrue(
            webpageMarkdown.markdown!!.length > 200, 
            "Markdown should have substantial content (got ${webpageMarkdown.markdown!!.length} chars)"
        )
        
        // Print the result for manual verification
        println("=== Batch Pipeline Result for $url ===")
        println("Title: ${webpageMarkdown.title}")
        println("Description: ${webpageMarkdown.description}")
        println("Markdown length: ${webpageMarkdown.markdown!!.length}")
        println(webpageMarkdown.markdown!!)
        println("...")
    }
}

