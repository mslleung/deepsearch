package io.deepsearch.presentation.dto

import io.deepsearch.domain.models.valueobjects.LanguagePattern
import io.deepsearch.domain.models.valueobjects.OcrLanguage
import io.deepsearch.domain.models.valueobjects.SearchMode
import kotlinx.serialization.Serializable

@Serializable
data class SearchRequest(
    val query: String,
    val url: String,
    val maxCacheAge: Long? = null,
    val mode: String? = null,  // "live-crawling" or "cache-only", defaults to "live-crawling"
    val languagePattern: String? = null,  // e.g., "/en-us/" or "?lang=en"
    val ocrLanguage: String? = null  // OCR language code (e.g., "eng", "chi_sim"), defaults to "eng"
) {
    /**
     * Parse the mode string to SearchMode enum.
     * Defaults to LIVE_CRAWLING if mode is null or invalid.
     */
    fun toSearchMode(): SearchMode {
        return when (mode?.lowercase()) {
            "cache-only" -> SearchMode.CACHE_ONLY
            "live-crawling" -> SearchMode.LIVE_CRAWLING
            null -> SearchMode.LIVE_CRAWLING
            else -> throw IllegalArgumentException("Invalid mode: '$mode'. Valid modes are: 'live-crawling', 'cache-only'")
        }
    }
    
    /**
     * Parse the OCR language string to OcrLanguage enum.
     * Defaults to ENGLISH if null or invalid.
     */
    fun toOcrLanguage(): OcrLanguage {
        return OcrLanguage.fromCodeOrDefault(ocrLanguage)
    }
    
    /**
     * Validate the language pattern if provided.
     * Throws IllegalArgumentException if the pattern is invalid.
     */
    fun validateLanguagePattern() {
        languagePattern?.let { pattern ->
            val error = LanguagePattern.validate(pattern)
            if (error != null) {
                throw IllegalArgumentException(error)
            }
        }
    }
    
    /**
     * Validate the OCR language if provided.
     * Throws IllegalArgumentException if the language code is invalid.
     */
    fun validateOcrLanguage() {
        ocrLanguage?.let { code ->
            val error = OcrLanguage.validate(code)
            if (error != null) {
                throw IllegalArgumentException(error)
            }
        }
    }
}