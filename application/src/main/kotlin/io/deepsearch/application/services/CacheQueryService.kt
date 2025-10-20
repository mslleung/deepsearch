package io.deepsearch.application.services

import io.deepsearch.domain.models.entities.WebpageMarkdown
import io.deepsearch.domain.repositories.IWebpageMarkdownRepository

interface ICacheQueryService {
    suspend fun listByDomain(domainPrefix: String, page: Int, size: Int): List<WebpageMarkdown>
    suspend fun getContent(url: String): WebpageMarkdown?
    suspend fun searchByUrl(query: String, page: Int, size: Int): List<WebpageMarkdown>
}

class CacheQueryService(
    private val repository: IWebpageMarkdownRepository
) : ICacheQueryService {

    override suspend fun listByDomain(domainPrefix: String, page: Int, size: Int): List<WebpageMarkdown> {
        val offset = (page - 1).coerceAtLeast(0) * size
        return repository.listByDomainPrefix(domainPrefix, offset, size)
    }

    override suspend fun getContent(url: String): WebpageMarkdown? {
        return repository.findByUrl(url)
    }

    override suspend fun searchByUrl(query: String, page: Int, size: Int): List<WebpageMarkdown> {
        val offset = (page - 1).coerceAtLeast(0) * size
        return repository.searchByUrl(query, offset, size)
    }
}

