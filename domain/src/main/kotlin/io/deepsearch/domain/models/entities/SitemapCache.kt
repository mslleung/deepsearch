package io.deepsearch.domain.models.entities

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class SitemapCache(
    val sitemapUrl: String,
    val xmlContent: String,
    val links: List<String>,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now()
) {
    fun isExpired(): Boolean {
        val cacheAgeMs = Clock.System.now().toEpochMilliseconds() -
                updatedAt.toEpochMilliseconds()
        val oneDayMs = 24 * 60 * 60 * 1000L

        return cacheAgeMs >= oneDayMs
    }
}

