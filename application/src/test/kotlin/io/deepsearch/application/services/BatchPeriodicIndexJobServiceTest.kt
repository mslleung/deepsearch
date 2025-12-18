package io.deepsearch.application.services

import io.deepsearch.application.services.batch.BatchPeriodicIndexEvent
import io.deepsearch.application.services.batch.IBatchPeriodicIndexOrchestrator
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJob
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJobState
import io.deepsearch.domain.models.entities.BatchUrlProcessingStage
import io.deepsearch.domain.models.entities.BatchUrlState
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.repositories.BatchUrlStageCounts
import io.deepsearch.domain.repositories.IBatchPeriodicIndexJobRepository
import io.deepsearch.domain.repositories.IBatchUrlStateRepository
import io.deepsearch.domain.services.INormalizeUrlService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.ExperimentalTime

/**
 * Unit tests for BatchPeriodicIndexJobService.
 */
@OptIn(ExperimentalTime::class)
class BatchPeriodicIndexJobServiceTest {

    private lateinit var service: BatchPeriodicIndexJobService
    private lateinit var mockJobRepository: MockBatchJobRepository
    private lateinit var mockUrlStateRepository: MockBatchUrlStateRepository
    private lateinit var mockOrchestrator: MockBatchOrchestrator
    private lateinit var mockNormalizeUrlService: MockNormalizeUrlService

    @BeforeEach
    fun setup() {
        mockJobRepository = MockBatchJobRepository()
        mockUrlStateRepository = MockBatchUrlStateRepository()
        mockOrchestrator = MockBatchOrchestrator()
        mockNormalizeUrlService = MockNormalizeUrlService()
        
        service = BatchPeriodicIndexJobService(
            normalizeUrlService = mockNormalizeUrlService,
            jobRepository = mockJobRepository,
            urlStateRepository = mockUrlStateRepository,
            orchestrator = mockOrchestrator
        )
    }

    @Test
    fun `start should create job and start orchestrator`() = runTest {
        val userId = UserId(1)
        val baseUrl = "https://example.com"
        val maxUrlCount = 100

        val job = service.start(
            baseUrl = baseUrl,
            maxUrlCount = maxUrlCount,
            userId = userId
        )

        assertNotNull(job.id)
        assertEquals(baseUrl, job.baseUrl)
        assertEquals(maxUrlCount, job.maxUrlCount)
        assertEquals(BatchPeriodicIndexJobState.CRAWL_AND_EXTRACT, job.state)
        assertEquals(userId, job.userId)
        
        // Verify orchestrator was started
        assertTrue(mockOrchestrator.startedJobs.contains(job.id))
    }

    @Test
    fun `stop should stop job via orchestrator`() = runTest {
        val userId = UserId(1)
        val job = service.start(
            baseUrl = "https://example.com",
            maxUrlCount = 100,
            userId = userId
        )
        val jobId = job.id!!

        service.stop(jobId)

        assertTrue(mockOrchestrator.stoppedJobs.contains(jobId))
    }

    @Test
    fun `findById should return job`() = runTest {
        val userId = UserId(1)
        val job = service.start(
            baseUrl = "https://example.com",
            maxUrlCount = 100,
            userId = userId
        )

        val found = service.findById(job.id!!)

        assertNotNull(found)
        assertEquals(job.id, found?.id)
    }

    @Test
    fun `getStats should return stage counts`() = runTest {
        val userId = UserId(1)
        val job = service.start(
            baseUrl = "https://example.com",
            maxUrlCount = 100,
            userId = userId
        )

        val stats = service.getStats(job.id!!)

        assertNotNull(stats)
        assertEquals(job.id, stats?.jobId)
        assertEquals(BatchPeriodicIndexJobState.CRAWL_AND_EXTRACT, stats?.state)
        assertEquals(1, stats?.stage)
    }

    @Test
    fun `listByUserId should return user jobs`() = runTest {
        val userId = UserId(1)
        val otherUserId = UserId(2)
        
        service.start(baseUrl = "https://example.com", maxUrlCount = 100, userId = userId)
        service.start(baseUrl = "https://other.com", maxUrlCount = 50, userId = otherUserId)
        
        val userJobs = service.listByUserId(userId)
        
        assertEquals(1, userJobs.size)
        assertEquals("https://example.com", userJobs[0].baseUrl)
    }

    // ========== Mock Implementations ==========

    private class MockNormalizeUrlService : INormalizeUrlService {
        override fun normalize(url: String, config: io.deepsearch.domain.services.UrlNormalizationConfig): String = url
    }

    private class MockBatchJobRepository : IBatchPeriodicIndexJobRepository {
        private val jobs = mutableMapOf<Long, BatchPeriodicIndexJob>()
        private var nextId = 1L
        val deletedIds = mutableListOf<Long>()

        override suspend fun create(job: BatchPeriodicIndexJob): BatchPeriodicIndexJob {
            job.id = nextId++
            jobs[job.id!!] = job
            return job
        }

        override suspend fun update(job: BatchPeriodicIndexJob) {
            jobs[job.id!!] = job
        }

        override suspend fun findById(id: Long): BatchPeriodicIndexJob? = jobs[id]

        override suspend fun findByUserId(userId: UserId): List<BatchPeriodicIndexJob> =
            jobs.values.filter { it.userId == userId }

        override suspend fun findByState(state: BatchPeriodicIndexJobState): List<BatchPeriodicIndexJob> =
            jobs.values.filter { it.state == state }

        override suspend fun findActiveJobs(): List<BatchPeriodicIndexJob> =
            jobs.values.filter { !it.isTerminal() }

        override suspend fun listAll(state: BatchPeriodicIndexJobState?): List<BatchPeriodicIndexJob> =
            if (state != null) jobs.values.filter { it.state == state } else jobs.values.toList()

        override suspend fun findLastCompletedByUserIdAndBaseUrl(
            userId: UserId,
            baseUrl: String
        ): BatchPeriodicIndexJob? =
            jobs.values
                .filter { it.userId == userId && it.baseUrl == baseUrl && it.state == BatchPeriodicIndexJobState.COMPLETED }
                .maxByOrNull { it.createdAt }

        override suspend fun delete(id: Long) {
            jobs.remove(id)
            deletedIds.add(id)
        }
    }

    private class MockBatchUrlStateRepository : IBatchUrlStateRepository {
        private val states = mutableMapOf<Long, BatchUrlState>()
        private var nextId = 1L
        val deletedJobIds = mutableListOf<Long>()

        override suspend fun create(urlState: BatchUrlState): BatchUrlState {
            urlState.id = nextId++
            states[urlState.id!!] = urlState
            return urlState
        }

        override suspend fun batchCreate(urlStates: List<BatchUrlState>) {
            urlStates.forEach { create(it) }
        }

        override suspend fun update(urlState: BatchUrlState) {
            states[urlState.id!!] = urlState
        }

        override suspend fun batchUpdate(urlStates: List<BatchUrlState>) {
            urlStates.forEach { update(it) }
        }

        override suspend fun findById(id: Long): BatchUrlState? = states[id]

        override suspend fun findByJobIdAndUrl(jobId: Long, url: String): BatchUrlState? =
            states.values.find { it.jobId == jobId && it.url == url }

        override suspend fun findByJobId(jobId: Long): List<BatchUrlState> =
            states.values.filter { it.jobId == jobId }

        override suspend fun findByJobIdAndStage(
            jobId: Long,
            stage: BatchUrlProcessingStage
        ): List<BatchUrlState> = states.values.filter { it.jobId == jobId && it.stage == stage }

        override suspend fun findNeedingContentLlmProcessing(jobId: Long): List<BatchUrlState> =
            states.values.filter { 
                it.jobId == jobId && 
                it.stage == BatchUrlProcessingStage.EXTRACTED && 
                it.errorMessage == null 
            }

        override suspend fun findNeedingFinalLlmProcessing(jobId: Long): List<BatchUrlState> =
            states.values.filter { 
                it.jobId == jobId && 
                it.stage == BatchUrlProcessingStage.CONTENT_LLM_DONE && 
                it.errorMessage == null 
            }

        override suspend fun findNeedingCaching(jobId: Long): List<BatchUrlState> =
            states.values.filter { 
                it.jobId == jobId && 
                it.stage == BatchUrlProcessingStage.FINAL_LLM_DONE && 
                it.errorMessage == null 
            }

        override suspend fun countByStage(jobId: Long): BatchUrlStageCounts {
            val jobStates = states.values.filter { it.jobId == jobId }
            return BatchUrlStageCounts(
                total = jobStates.size,
                pending = jobStates.count { it.stage == BatchUrlProcessingStage.PENDING },
                extracted = jobStates.count { it.stage == BatchUrlProcessingStage.EXTRACTED },
                contentLlmDone = jobStates.count { it.stage == BatchUrlProcessingStage.CONTENT_LLM_DONE },
                finalLlmDone = jobStates.count { it.stage == BatchUrlProcessingStage.FINAL_LLM_DONE },
                cached = jobStates.count { it.stage == BatchUrlProcessingStage.CACHED },
                failed = jobStates.count { it.errorMessage != null }
            )
        }

        override suspend fun deleteByJobId(jobId: Long) {
            states.entries.removeIf { it.value.jobId == jobId }
            deletedJobIds.add(jobId)
        }

        override suspend fun existsByJobIdAndUrl(jobId: Long, url: String): Boolean =
            states.values.any { it.jobId == jobId && it.url == url }
    }

    private class MockBatchOrchestrator : IBatchPeriodicIndexOrchestrator {
        val startedJobs = mutableListOf<Long>()
        val stoppedJobs = mutableListOf<Long>()
        private val eventFlows = mutableMapOf<Long, MutableSharedFlow<BatchPeriodicIndexEvent>>()

        override suspend fun startOrResume(job: BatchPeriodicIndexJob) {
            startedJobs.add(job.id!!)
            eventFlows[job.id!!] = MutableSharedFlow(replay = 1)
        }

        override suspend fun stop(jobId: Long) {
            stoppedJobs.add(jobId)
        }

        override fun events(jobId: Long): SharedFlow<BatchPeriodicIndexEvent> =
            eventFlows[jobId] ?: MutableSharedFlow(replay = 1)
    }
}
