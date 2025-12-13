package io.deepsearch.application.services

import io.deepsearch.domain.models.entities.PeriodicIndexJob
import io.deepsearch.domain.models.entities.PeriodicIndexJobState
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.repositories.IPeriodicIndexJobRepository
import io.deepsearch.domain.services.INormalizeUrlService
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

interface IPeriodicIndexJobService {
    @Serializable
    data class ProcessedUrlInfo(
        val url: String,
        val title: String?,
        val cachedHit: Boolean,
        val processedAtMs: Long
    )

    @Serializable
    data class FailedUrlInfo(
        val url: String,
        val errorMessage: String,
        val failedAtMs: Long
    )

    @Serializable
    data class PeriodicIndexEvent(
        val jobId: Long,
        val baseUrl: String,
        val url: String?,
        val processedCount: Int,
        val maxUrlCount: Int,
        val cachedHit: Boolean?,
        val totalQueued: Int,
        val state: PeriodicIndexJobState,
        val message: String? = null,
        val timestampMs: Long = System.currentTimeMillis(),
        // Enhanced URL tracking
        val processedUrls: List<ProcessedUrlInfo> = emptyList(),
        val processingUrls: List<String> = emptyList(),
        val failedUrls: List<FailedUrlInfo> = emptyList()
    )

    suspend fun start(baseUrl: String, maxUrlCount: Int, sitemapUrl: String? = null, languagePattern: String? = null, userId: UserId): PeriodicIndexJob
    suspend fun stop(jobId: Long)
    suspend fun findById(jobId: Long): PeriodicIndexJob?
    suspend fun list(state: PeriodicIndexJobState? = null): List<PeriodicIndexJob>
    fun events(jobId: Long): SharedFlow<PeriodicIndexEvent>
}

@OptIn(ExperimentalTime::class)
class PeriodicIndexJobService(
    private val normalizeUrlService: INormalizeUrlService,
    private val jobRepository: IPeriodicIndexJobRepository,
    private val registry: IPeriodicIndexJobRegistry
) : IPeriodicIndexJobService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun start(baseUrl: String, maxUrlCount: Int, sitemapUrl: String?, languagePattern: String?, userId: UserId): PeriodicIndexJob {
        val normalizedBase = normalize(baseUrl)
        val now = Clock.System.now()
        val created = jobRepository.create(
            PeriodicIndexJob(
                id = null,
                userId = userId,
                baseUrl = normalizedBase,
                maxUrlCount = maxUrlCount,
                sitemapUrl = sitemapUrl,
                createdAt = now,
                updatedAt = now,
                processedCount = 0,
                state = PeriodicIndexJobState.IN_PROGRESS,
                languagePattern = languagePattern
            )
        )
        registry.ensureRunning(created)
        return created
    }

    override suspend fun stop(jobId: Long) {
        registry.stop(jobId)
    }

    override suspend fun findById(jobId: Long): PeriodicIndexJob? = jobRepository.findById(jobId)

    override suspend fun list(state: PeriodicIndexJobState?): List<PeriodicIndexJob> = jobRepository.listAll(state)

    override fun events(jobId: Long): SharedFlow<IPeriodicIndexJobService.PeriodicIndexEvent> = registry.events(jobId)

    private fun normalize(url: String): String = normalizeUrlService.normalize(url) ?: url
}

