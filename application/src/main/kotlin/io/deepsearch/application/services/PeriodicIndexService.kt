package io.deepsearch.application.services

import io.deepsearch.domain.models.entities.PeriodicIndexConfig
import io.deepsearch.domain.models.entities.PeriodicIndexJob
import io.deepsearch.domain.models.entities.SubscriptionPlan
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.repositories.IPeriodicIndexConfigRepository
import io.deepsearch.domain.repositories.IPeriodicIndexJobRepository
import kotlin.time.ExperimentalTime

class PeriodicIndexLimitExceededException(
    val currentCount: Int,
    val maxAllowed: Int
) : RuntimeException("Periodic index config limit exceeded: $currentCount/$maxAllowed")

interface IPeriodicIndexService {
    // Config management
    suspend fun listConfigs(userId: UserId): List<PeriodicIndexConfig>
    suspend fun getConfig(configId: Long): PeriodicIndexConfig?
    suspend fun getConfigCount(userId: UserId): Int
    suspend fun createConfig(userId: UserId, url: String, sitemapUrl: String?, periodDays: Int?, maxUrlCount: Int, languagePattern: String?, maxAllowedConfigs: Int): PeriodicIndexConfig
    suspend fun updateConfig(configId: Long, url: String, sitemapUrl: String?, periodDays: Int?, maxUrlCount: Int, languagePattern: String?): PeriodicIndexConfig
    suspend fun deleteConfig(configId: Long)
    suspend fun enableConfig(configId: Long): PeriodicIndexConfig
    suspend fun disableConfig(configId: Long): PeriodicIndexConfig
    
    // Job triggering
    suspend fun triggerNow(configId: Long): PeriodicIndexJob
    
    // Job history
    suspend fun listJobHistory(userId: UserId, page: Int, pageSize: Int): Pair<List<PeriodicIndexJob>, Int>
    suspend fun listJobHistoryForConfig(userId: UserId, baseUrl: String, page: Int, pageSize: Int): Pair<List<PeriodicIndexJob>, Int>
    
    // For scheduler
    suspend fun checkAndRunDueConfigs()
}

@OptIn(ExperimentalTime::class)
class PeriodicIndexService(
    private val periodicIndexConfigRepository: IPeriodicIndexConfigRepository,
    private val periodicIndexJobService: IPeriodicIndexJobService,
    private val periodicIndexJobRepository: IPeriodicIndexJobRepository
) : IPeriodicIndexService {

    override suspend fun listConfigs(userId: UserId): List<PeriodicIndexConfig> {
        return periodicIndexConfigRepository.findAllByUserId(userId)
    }

    override suspend fun getConfig(configId: Long): PeriodicIndexConfig? {
        return periodicIndexConfigRepository.findById(configId)
    }

    override suspend fun getConfigCount(userId: UserId): Int {
        return periodicIndexConfigRepository.countByUserId(userId)
    }

    override suspend fun createConfig(
        userId: UserId, 
        url: String, 
        sitemapUrl: String?, 
        periodDays: Int?, 
        maxUrlCount: Int,
        languagePattern: String?,
        maxAllowedConfigs: Int
    ): PeriodicIndexConfig {
        // Check limit
        val currentCount = periodicIndexConfigRepository.countByUserId(userId)
        if (currentCount >= maxAllowedConfigs) {
            throw PeriodicIndexLimitExceededException(currentCount, maxAllowedConfigs)
        }
        
        val newConfig = PeriodicIndexConfig(
            userId = userId,
            url = url,
            sitemapUrl = sitemapUrl,
            periodDays = periodDays,
            maxUrlCount = maxUrlCount,
            languagePattern = languagePattern
        )
        return periodicIndexConfigRepository.create(newConfig)
    }

    override suspend fun updateConfig(
        configId: Long, 
        url: String, 
        sitemapUrl: String?, 
        periodDays: Int?, 
        maxUrlCount: Int,
        languagePattern: String?
    ): PeriodicIndexConfig {
        val existing = periodicIndexConfigRepository.findById(configId)
            ?: throw IllegalArgumentException("Config not found: $configId")
        
        existing.updateConfig(url, sitemapUrl, periodDays, maxUrlCount, languagePattern)
        if (!existing.enabled) {
            existing.enable() // Re-enable if it was disabled
        }
        return periodicIndexConfigRepository.update(existing)
    }

    override suspend fun deleteConfig(configId: Long) {
        val config = periodicIndexConfigRepository.findById(configId) ?: return
        
        // Stop any active job for this config's URL before deleting
        val activeJob = periodicIndexJobRepository.findActiveByBaseUrl(config.url)
        if (activeJob != null && activeJob.id != null) {
            periodicIndexJobService.stop(activeJob.id!!)
        }
        
        periodicIndexConfigRepository.delete(config)
    }

    override suspend fun enableConfig(configId: Long): PeriodicIndexConfig {
        val config = periodicIndexConfigRepository.findById(configId)
            ?: throw IllegalArgumentException("Config not found: $configId")
        
        config.enable()
        return periodicIndexConfigRepository.update(config)
    }

    override suspend fun disableConfig(configId: Long): PeriodicIndexConfig {
        val config = periodicIndexConfigRepository.findById(configId)
            ?: throw IllegalArgumentException("Config not found: $configId")
        
        config.disable()
        return periodicIndexConfigRepository.update(config)
    }

    override suspend fun triggerNow(configId: Long): PeriodicIndexJob {
        val config = periodicIndexConfigRepository.findById(configId)
            ?: throw IllegalArgumentException("No periodic index configuration found: $configId")

        // Start the job with the sitemap URL and language pattern from config
        val job = periodicIndexJobService.start(
            baseUrl = config.url,
            maxUrlCount = config.maxUrlCount,
            sitemapUrl = config.sitemapUrl,
            languagePattern = config.languagePattern,
            userId = config.userId
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

    override suspend fun listJobHistoryForConfig(userId: UserId, baseUrl: String, page: Int, pageSize: Int): Pair<List<PeriodicIndexJob>, Int> {
        val offset = (page - 1) * pageSize
        val jobs = periodicIndexJobRepository.listByUserIdAndBaseUrl(userId, baseUrl, state = null, offset = offset, limit = pageSize)
        val total = periodicIndexJobRepository.countByUserIdAndBaseUrl(userId, baseUrl, state = null).toInt()
        return jobs to total
    }

    override suspend fun checkAndRunDueConfigs() {
        // Fetch all enabled configs and filter by isDue() in the domain model
        val enabledConfigs = periodicIndexConfigRepository.findEnabledConfigs()
        val dueConfigs = enabledConfigs.filter { it.isDue() }
        
        for (config in dueConfigs) {
            try {
                // Start job with sitemap URL and language pattern from config
                periodicIndexJobService.start(
                    baseUrl = config.url,
                    maxUrlCount = config.maxUrlCount,
                    sitemapUrl = config.sitemapUrl,
                    languagePattern = config.languagePattern,
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
