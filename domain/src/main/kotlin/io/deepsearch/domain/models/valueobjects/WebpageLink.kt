package io.deepsearch.domain.models.valueobjects

// the source of discovery
enum class LinkSource {
    GOOGLE_SEARCH,
    SERPER_SEARCH,
    LINK_RELEVANCE,
    ALL_LINKS,
    SITEMAP,
    VECTOR_SIMILARITY,
    FILE_CONTENT  // URLs extracted from file content (PDFs, docs, etc.)
}

data class WebpageLink(
    val url: String,
    val source: LinkSource,
    val reason: String
)

