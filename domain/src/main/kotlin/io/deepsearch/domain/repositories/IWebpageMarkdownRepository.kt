package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.WebpageMarkdown

interface IWebpageMarkdownRepository {
    suspend fun findByUrl(url: String): WebpageMarkdown?
    suspend fun upsert(webpage: WebpageMarkdown)
}

