package io.deepsearch.application.services

import io.deepsearch.domain.models.entities.PeriodicIndexConfig
import io.deepsearch.domain.models.entities.PrecacheJob
import io.deepsearch.domain.models.entities.PrecacheJobState
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.repositories.IPeriodicIndexConfigRepository
import io.deepsearch.domain.repositories.IPrecacheJobRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.ExperimentalTime

interface IPeriodicIndexService {
    suspend fun getConfig(userId: UserId): PeriodicIndexConfig?
    suspend fun createOrUpdateConfig(userId: UserId, url: String, periodDays: Int?): PeriodicIndexConfig
    suspend fun deleteConfig(userId: UserId)
    suspend fun triggerNow(userId: UserId): PrecacheJob
    suspend fun listJobHistory(userId: UserId, page: Int, pageSize: Int): Pair<List<PrecacheJob>, Int>
    
    // For scheduler
    suspend fun checkAndRunDueConfigs()
}

@OptIn(ExperimentalTime::class)
class PeriodicIndexService(
    private val periodicIndexConfigRepository: IPeriodicIndexConfigRepository,
    private val precacheService: IPrecacheService,
    private val precacheJobRepository: IPrecacheJobRepository
) : IPeriodicIndexService {

    override suspend fun getConfig(userId: UserId): PeriodicIndexConfig? {
        return periodicIndexConfigRepository.findByUserId(userId)
    }

    override suspend fun createOrUpdateConfig(userId: UserId, url: String, periodDays: Int?): PeriodicIndexConfig {
        val existing = periodicIndexConfigRepository.findByUserId(userId)
        return if (existing != null) {
            existing.updateConfig(url, periodDays)
            if (!existing.enabled) {
                existing.enable() // Re-enable if it was disabled
            }
            periodicIndexConfigRepository.update(existing)
        } else {
            val newConfig = PeriodicIndexConfig(
                userId = userId,
                url = url,
                periodDays = periodDays
            )
            periodicIndexConfigRepository.create(newConfig)
        }
    }

    override suspend fun deleteConfig(userId: UserId) {
        val config = periodicIndexConfigRepository.findByUserId(userId) ?: return
        periodicIndexConfigRepository.delete(config)
    }

    override suspend fun triggerNow(userId: UserId): PrecacheJob {
        val config = periodicIndexConfigRepository.findByUserId(userId)
            ?: throw IllegalArgumentException("No periodic index configuration found for user")

        // Start the precache job
        // Note: We are passing userId to start() which will need to be added to IPrecacheService
        val job = precacheService.start(
            baseUrl = config.url,
            maxUrlCount = 100, // Default limit for periodic jobs
            sitemapUrl = null, // Sitemap discovery handled internally if needed, or added to config later
            userId = userId
        )

        // Update config's last run time
        config.markAsRun()
        periodicIndexConfigRepository.update(config)

        return job
    }

    override suspend fun listJobHistory(userId: UserId, page: Int, pageSize: Int): Pair<List<PrecacheJob>, Int> {
        // This requires a new method on IPrecacheJobRepository to filter by userId
        // For now, we'll fetch all and filter in memory (inefficient but works for MVP with low volume)
        // TODO: Implement proper pagination in repository
        val allJobs = precacheJobRepository.listAll(null)
            .filter { it.userId == userId }
            .sortedByDescending { it.createdAt.toEpochMilliseconds() }

        val total = allJobs.size
        val fromIndex = (page - 1) * pageSize
        if (fromIndex >= total) return emptyList<PrecacheJob>() to total
        
        val toIndex = minOf(fromIndex + pageSize, total)
        return allJobs.subList(fromIndex, toIndex) to total
    }

    override suspend fun checkAndRunDueConfigs() {
        val dueConfigs = periodicIndexConfigRepository.findDueConfigs(limit = 10)
        for (config in dueConfigs) {
            try {
                // Start job
                precacheService.start(
                    baseUrl = config.url,
                    maxUrlCount = 100,
                    sitemapUrl = null,
                    userId = config.userId
                )

                // Update config
                config.markAsRun()
                periodicIndexConfigRepository.update(config)
            } catch (e: Exception) {
                // Log error but continue with other configs
                // In a real system, we might want to disable config after N failures
                println("Failed to run periodic index for config ${config.id}: ${e.message}")
            }
        }
    }
}
