package io.deepsearch.domain.models.valueobjects

import java.net.URI

/**
 * Represents a search query with URL and optional language filter.
 * 
 * @param query The search query text
 * @param url The base URL to search within
 * @param languagePattern Optional language filter pattern (e.g., "/en-us/" or "?lang=en")
 * @param ocrLanguage OCR language for Tesseract text extraction from images
 */
data class SearchQuery(
    val query: String, 
    val url: String,
    val languagePattern: String? = null,
    val ocrLanguage: OcrLanguage = OcrLanguage.DEFAULT
) {
    
    /**
     * Parsed language pattern for URL filtering. Null if no pattern or invalid pattern.
     */
    val parsedLanguagePattern: LanguagePattern? = languagePattern?.let { LanguagePattern.parse(it) }

    init {
        require(query.isNotBlank()) { "Search query cannot be blank" }
        require(query.length <= 1000) { "Search query cannot exceed 1000 characters" }

        require(url.isNotBlank()) { "URL cannot be blank" }

        val urlObj = URI.create(url).toURL()
        require(urlObj.protocol in listOf("http", "https")) {
            "URL must use HTTP or HTTPS protocol"
        }
        
        // Validate language pattern if provided
        languagePattern?.let { pattern ->
            val error = LanguagePattern.validate(pattern)
            require(error == null) { error ?: "Invalid language pattern" }
        }
    }
}
