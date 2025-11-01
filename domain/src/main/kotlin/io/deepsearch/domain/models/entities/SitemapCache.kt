package io.deepsearch.domain.models.entities

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
data class SitemapCache(
    val sitemapUrl: String,
    val xmlContent: String,
    val linksJson: String, // JSON array of URLs
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now()
)

