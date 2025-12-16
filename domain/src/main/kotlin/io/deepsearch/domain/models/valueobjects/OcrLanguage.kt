package io.deepsearch.domain.models.valueobjects

/**
 * Supported languages for Tesseract OCR text extraction.
 * Each language corresponds to a traineddata file bundled in the resources.
 *
 * @param code Tesseract language code (matches traineddata filename without extension)
 * @param displayName Human-readable language name for UI display
 */
enum class OcrLanguage(val code: String, val displayName: String) {
    ENGLISH("eng", "English"),
    CHINESE_SIMPLIFIED("chi_sim", "Chinese (Simplified)"),
    CHINESE_TRADITIONAL("chi_tra", "Chinese (Traditional)"),
    SPANISH("spa", "Spanish"),
    FRENCH("fra", "French"),
    GERMAN("deu", "German"),
    JAPANESE("jpn", "Japanese"),
    KOREAN("kor", "Korean"),
    PORTUGUESE("por", "Portuguese"),
    RUSSIAN("rus", "Russian"),
    ARABIC("ara", "Arabic"),
    ITALIAN("ita", "Italian");

    companion object {
        val DEFAULT = ENGLISH

        /**
         * Find OcrLanguage by Tesseract language code.
         * @param code Tesseract language code (e.g., "eng", "chi_sim")
         * @return Matching OcrLanguage or null if not found
         */
        fun fromCode(code: String?): OcrLanguage? = 
            if (code == null) null else entries.find { it.code == code }

        /**
         * Find OcrLanguage by code, returning DEFAULT if not found.
         * @param code Tesseract language code
         * @return Matching OcrLanguage or DEFAULT
         */
        fun fromCodeOrDefault(code: String?): OcrLanguage = 
            fromCode(code) ?: DEFAULT

        /**
         * Validate if a language code is supported.
         * @param code Language code to validate
         * @return null if valid, error message if invalid
         */
        fun validate(code: String): String? {
            return if (fromCode(code) == null) {
                "Invalid OCR language code: '$code'. Supported codes are: ${entries.joinToString(", ") { it.code }}"
            } else {
                null
            }
        }
    }
}

