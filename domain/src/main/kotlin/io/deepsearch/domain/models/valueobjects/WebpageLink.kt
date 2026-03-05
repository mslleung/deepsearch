package io.deepsearch.domain.models.valueobjects

// the source of discovery
enum class LinkSource {
    GOOGLE_SEARCH,
    SERPER_SEARCH,
    LINK_RELEVANCE,
    ALL_LINKS,
    SITEMAP,
    FILE_CONTENT,  // URLs extracted from file content (PDFs, docs, etc.)
    KNOWLEDGE_GRAPH,  // Links discovered from KG entity source URLs
    HYBRID_SEARCH,  // Links discovered from hybrid (vector + keyword) search of cache
    FILE_SEARCH,  // Links discovered from Gemini file search
    AGENTIC_NAVIGATION  // Links discovered via agentic page navigation (click triggered full-page navigation)
}

/**
 * Represents a discovered link to a webpage.
 * 
 * @property url The URL of the webpage
 * @property source How this link was discovered
 * @property reason Why this link is relevant to the query
 * @property score Relevance score (1-10, higher = more relevant). Used for priority queue processing.
 *                 Null if source doesn't provide scoring.
 */
data class WebpageLink(
    val url: String,
    val source: LinkSource,
    val reason: String,
    val score: Int? = null
)

