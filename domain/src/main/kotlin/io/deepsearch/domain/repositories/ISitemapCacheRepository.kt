package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.SitemapCache

interface ISitemapCacheRepository {
    suspend fun findByUrl(sitemapUrl: String): SitemapCache?
    suspend fun upsert(cache: SitemapCache)
}

