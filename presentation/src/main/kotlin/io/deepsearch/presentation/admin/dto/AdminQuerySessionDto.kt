package io.deepsearch.presentation.admin.dto

import io.deepsearch.domain.models.entities.QuerySession
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@Serializable
data class AdminQuerySessionDto(
    val id: String,
    val query: String,
    val url: String,
    val state: String,
    val finishReason: String?,
    val answerComplete: Boolean,
    val answer: String?,
    val traversedUrlCount: Int,
    val sourcesDiscoveredCount: Int,
    val createdAt: Long, // epoch millis
    val updatedAt: Long
)

@OptIn(ExperimentalTime::class)
fun QuerySession.toAdminDto(): AdminQuerySessionDto {
    return AdminQuerySessionDto(
        id = this.id,
        query = this.query,
        url = this.url,
        state = this.state.name,
        finishReason = this.finishReason?.name,
        answerComplete = this.answerComplete,
        answer = this.answer,
        traversedUrlCount = this.traversedUrls.size,
        sourcesDiscoveredCount = this.sourcesDiscovered.size,
        createdAt = this.createdAt.toEpochMilliseconds(),
        updatedAt = this.updatedAt.toEpochMilliseconds()
    )
}

@Serializable
data class AdminQuerySessionDetailDto(
    val id: String,
    val query: String,
    val url: String,
    val state: String,
    val finishReason: String?,
    val answerComplete: Boolean,
    val answer: String?,
    val traversedUrls: List<String>,
    val sourcesDiscovered: List<String>,
    val createdAt: Long,
    val updatedAt: Long
)

@OptIn(ExperimentalTime::class)
fun QuerySession.toAdminDetailDto(): AdminQuerySessionDetailDto {
    return AdminQuerySessionDetailDto(
        id = this.id,
        query = this.query,
        url = this.url,
        state = this.state.name,
        finishReason = this.finishReason?.name,
        answerComplete = this.answerComplete,
        answer = this.answer,
        traversedUrls = this.traversedUrls.toList(),
        sourcesDiscovered = this.sourcesDiscovered.toList(),
        createdAt = this.createdAt.toEpochMilliseconds(),
        updatedAt = this.updatedAt.toEpochMilliseconds()
    )
}

