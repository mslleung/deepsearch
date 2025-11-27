package io.deepsearch.application.services

import io.deepsearch.domain.models.entities.PeriodicIndexConfig
import io.deepsearch.domain.models.entities.PeriodicIndexJob
import io.deepsearch.domain.models.entities.PeriodicIndexJobState
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.repositories.IPeriodicIndexConfigRepository
import io.deepsearch.domain.repositories.IPeriodicIndexJobRepository
import kotlin.time.ExperimentalTime

interface IPeriodicIndexService {
    suspend fun getConfig(userId: UserId): PeriodicIndexConfig?
    suspend fun createOrUpdateConfig(userId: UserId, url: String, sitemapUrl: String?, periodDays: Int?, maxUrlCount: Int): PeriodicIndexConfig
    suspend fun deleteConfig(userId: UserId)
    suspend fun triggerNow(userId: UserId): PeriodicIndexJob
    suspend fun listJobHistory(userId: UserId, page: Int, pageSize: Int): Pair<List<PeriodicIndexJob>, Int>
    
    // For scheduler
    suspend fun checkAndRunDueConfigs()
}

@OptIn(ExperimentalTime::class)
class PeriodicIndexService(
    private val periodicIndexConfigRepository: IPeriodicIndexConfigRepository,
    private val periodicIndexJobService: IPeriodicIndexJobService,
    private val periodicIndexJobRepository: IPeriodicIndexJobRepository
) : IPeriodicIndexService {

    override suspend fun getConfig(userId: UserId): PeriodicIndexConfig? {
        return periodicIndexConfigRepository.findByUserId(userId)
    }

    override suspend fun createOrUpdateConfig(userId: UserId, url: String, sitemapUrl: String?, periodDays: Int?, maxUrlCount: Int): PeriodicIndexConfig {
        val existing = periodicIndexConfigRepository.findByUserId(userId)
        return if (existing != null) {
            existing.updateConfig(url, sitemapUrl, periodDays, maxUrlCount)
            if (!existing.enabled) {
                existing.enable() // Re-enable if it was disabled
            }
            periodicIndexConfigRepository.update(existing)
        } else {
            val newConfig = PeriodicIndexConfig(
                userId = userId,
                url = url,
                sitemapUrl = sitemapUrl,
                periodDays = periodDays,
                maxUrlCount = maxUrlCount
            )
            periodicIndexConfigRepository.create(newConfig)
        }
    }

    override suspend fun deleteConfig(userId: UserId) {
        val config = periodicIndexConfigRepository.findByUserId(userId) ?: return
        periodicIndexConfigRepository.delete(config)
    }

    override suspend fun triggerNow(userId: UserId): PeriodicIndexJob {
        val config = periodicIndexConfigRepository.findByUserId(userId)
            ?: throw IllegalArgumentException("No periodic index configuration found for user")

        // Start the job with the sitemap URL from config
        val job = periodicIndexJobService.start(
            baseUrl = config.url,
            maxUrlCount = config.maxUrlCount,
            sitemapUrl = config.sitemapUrl,
            userId = userId
        )

        // Update config's last run time
        config.markAsRun()
        periodicIndexConfigRepository.update(config)

        return job
    }

    override suspend fun listJobHistory(userId: UserId, page: Int, pageSize: Int): Pair<List<PeriodicIndexJob>, Int> {
        val offset = (page - 1) * pageSize
        val jobs = periodicIndexJobRepository.listByUserId(userId, state = null, offset = offset, limit = pageSize)
        val total = periodicIndexJobRepository.countByUserId(userId, state = null).toInt()
        return jobs to total
    }

    override suspend fun checkAndRunDueConfigs() {
        // Fetch all enabled configs and filter by isDue() in the domain model
        val enabledConfigs = periodicIndexConfigRepository.findEnabledConfigs()
        val dueConfigs = enabledConfigs.filter { it.isDue() }
        
        for (config in dueConfigs) {
            try {
                // Start job with sitemap URL from config
                periodicIndexJobService.start(
                    baseUrl = config.url,
                    maxUrlCount = config.maxUrlCount,
                    sitemapUrl = config.sitemapUrl,
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
