package io.deepsearch.presentation.admin.dto

import io.deepsearch.application.services.BatchJobStats
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJob
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime

/**
 * DTO for batch periodic index job in admin UI.
 */
@OptIn(ExperimentalTime::class)
@Serializable
data class AdminBatchPeriodicIndexJobDto(
    val id: Long,
    val userId: Int,
    val baseUrl: String,
    val maxUrlCount: Int,
    val sitemapUrl: String?,
    val languagePattern: String?,
    val state: String,
    val stage: Int,
    val stageDescription: String,
    val urlsProcessed: Int,
    val urlsContentProcessed: Int,
    val urlsFinalProcessed: Int,
    val urlsCached: Int,
    val batchJobIds: List<String>,
    val createdAt: Long, // epoch millis
    val updatedAt: Long
)

/**
 * DTO for batch periodic index job statistics.
 */
@Serializable
data class AdminBatchJobStatsDto(
    val jobId: Long,
    val state: String,
    val stage: Int,
    val stageDescription: String,
    val totalUrls: Int,
    val processedUrls: Int,
    val contentProcessedUrls: Int,
    val finalProcessedUrls: Int,
    val cachedUrls: Int,
    val failedUrls: Int,
    val estimatedCompletionTimeMs: Long?
)

@OptIn(ExperimentalTime::class)
fun BatchPeriodicIndexJob.toAdminDto(): AdminBatchPeriodicIndexJobDto {
    return AdminBatchPeriodicIndexJobDto(
        id = this.id!!,
        userId = this.userId.value,
        baseUrl = this.baseUrl,
        maxUrlCount = this.maxUrlCount,
        sitemapUrl = this.sitemapUrl,
        languagePattern = this.languagePattern,
        state = this.state.name,
        stage = this.currentStage(),
        stageDescription = this.stageDescription(),
        urlsProcessed = this.urlsProcessed,
        urlsContentProcessed = this.urlsContentProcessed,
        urlsFinalProcessed = this.urlsFinalProcessed,
        urlsCached = this.urlsCached,
        batchJobIds = this.batchJobIds,
        createdAt = this.createdAt.toEpochMilliseconds(),
        updatedAt = this.updatedAt.toEpochMilliseconds()
    )
}

fun BatchJobStats.toAdminDto(): AdminBatchJobStatsDto {
    return AdminBatchJobStatsDto(
        jobId = this.jobId,
        state = this.state.name,
        stage = this.stage,
        stageDescription = this.stageDescription,
        totalUrls = this.totalUrls,
        processedUrls = this.processedUrls,
        contentProcessedUrls = this.contentProcessedUrls,
        finalProcessedUrls = this.finalProcessedUrls,
        cachedUrls = this.cachedUrls,
        failedUrls = this.failedUrls,
        estimatedCompletionTimeMs = this.estimatedCompletionTimeMs
    )
}
