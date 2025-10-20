package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.WebpageMarkdown

interface IWebpageMarkdownRepository {
    suspend fun findByUrl(url: String): WebpageMarkdown?
    suspend fun upsert(webpage: WebpageMarkdown)
    suspend fun listByDomainPrefix(prefix: String, offset: Int, limit: Int): List<WebpageMarkdown>
    suspend fun countByDomainPrefix(prefix: String): Long
    suspend fun searchByUrl(query: String, offset: Int, limit: Int): List<WebpageMarkdown>
    suspend fun countSearchByUrl(query: String): Long
}

