package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.valueobjects.CachedWebsiteContext

/**
 * Repository for caching website context per URL.
 * Used to avoid re-fetching and re-processing HTML for subsequent queries on the same page.
 */
interface IWebsiteContextRepository {
    /**
     * Find cached website context by URL.
     * @param url The normalized URL to look up
     * @return The cached context if found, null otherwise
     */
    suspend fun findByUrl(url: String): CachedWebsiteContext?
    
    /**
     * Upsert website context (insert or update if exists).
     * @param context The context to cache
     */
    suspend fun upsert(context: CachedWebsiteContext)
}

