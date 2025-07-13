package io.deepsearch.domain.entities

import io.deepsearch.domain.valueobjects.WebUrl
import io.deepsearch.domain.valueobjects.SearchQuery

data class WebScrapeResult(
    val url: WebUrl,
    val query: SearchQuery,
    val response: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun isSuccessful(): Boolean = response.isNotBlank()
    
    fun getResponseLength(): Int = response.length
    
    fun getElapsedTime(startTime: Long): Long = timestamp - startTime
} 