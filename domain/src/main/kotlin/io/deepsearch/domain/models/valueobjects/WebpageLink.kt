package io.deepsearch.domain.models.valueobjects

enum class LinkSource {
    GOOGLE_SEARCH,
    LINK_RELEVANCE
}

data class WebpageLink(
    val url: String,
    val source: LinkSource,
    val reason: String
)

