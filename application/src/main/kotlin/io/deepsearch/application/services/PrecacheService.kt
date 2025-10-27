package io.deepsearch.application.services

import io.deepsearch.domain.models.entities.PrecacheJob
import io.deepsearch.domain.models.entities.PrecacheJobState
import io.deepsearch.domain.repositories.IPrecacheJobRepository
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

interface IPrecacheService {
    @Serializable
    data class PrecacheEvent(
        val jobId: Long,
        val baseUrl: String,
        val url: String?,
        val processedCount: Int,
        val maxUrlCount: Int,
        val cachedHit: Boolean?,
        val totalQueued: Int,
        val state: PrecacheJobState,
        val message: String? = null,
        val timestampMs: Long = System.currentTimeMillis()
    )

    suspend fun start(baseUrl: String, maxUrlCount: Int): PrecacheJob
    suspend fun stop(jobId: Long)
    suspend fun list(state: PrecacheJobState? = null): List<PrecacheJob>
    fun events(jobId: Long): SharedFlow<IPrecacheService.PrecacheEvent>
}

@OptIn(ExperimentalTime::class)
class PrecacheService(
    private val normalizeUrlService: INormalizeUrlService,
    private val jobRepository: IPrecacheJobRepository,
    private val registry: IPrecacheJobRegistry
) : IPrecacheService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun start(baseUrl: String, maxUrlCount: Int): PrecacheJob {
        val normalizedBase = normalize(baseUrl)
        val existing = jobRepository.findActiveByBaseUrl(normalizedBase)
        if (existing != null) return existing

        val now = Clock.System.now()
        val created = jobRepository.create(
            PrecacheJob(
                id = null,
                baseUrl = normalizedBase,
                maxUrlCount = maxUrlCount,
                createdAt = now,
                updatedAt = now,
                processedCount = 0,
                state = PrecacheJobState.IN_PROGRESS
            )
        )
        registry.ensureRunning(created)
        return created
    }

    override suspend fun stop(jobId: Long) {
        registry.stop(jobId)
    }

    override suspend fun list(state: PrecacheJobState?): List<PrecacheJob> = jobRepository.listAll(state)

    override fun events(jobId: Long): SharedFlow<IPrecacheService.PrecacheEvent> = registry.events(jobId)

    private fun normalize(url: String): String = normalizeUrlService.normalize(url) ?: url
}


