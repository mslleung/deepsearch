package io.deepsearch.application.services

import com.ibm.icu.text.BreakIterator
import com.ibm.icu.util.ULocale
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.regex.Pattern

/**
 * Result of HTML preview preparation.
 * 
 * Contains extracted sentences (prose content only) instead of cleaned HTML.
 * This approach is token-efficient and naturally filters out table/grid data
 * since table cells are fragments, not sentences.
 */
data class HtmlPreviewResult(
    val extractedSentences: String,
    val title: String?,
    val description: String?
)

interface IHtmlPreviewService {
    /**
     * Prepare a preview by extracting sentences from HTML content.
     * 
     * Strategy: Extract only full sentences using ICU4J for multilingual support.
     * This naturally filters out tabular data because:
     * - Table cells are short fragments: "Pro Plan", "$99/mo", "✓"
     * - Prose content forms complete sentences
     * 
     * Uses ICU4J BreakIterator for language-agnostic sentence detection,
     * then filters by minimum length to exclude fragments.
     * 
     * @param html The raw HTML content
     * @param url The URL of the page (for logging)
     * @return Extracted sentences with metadata
     */
    fun prepareHtmlPreview(html: String, url: String): HtmlPreviewResult
}

/**
 * HTML preview service using sentence extraction for token efficiency.
 * 
 * Instead of passing verbose HTML to the LLM, this service:
 * 1. Strips HTML to plain text
 * 2. Uses ICU4J BreakIterator for multilingual sentence detection
 * 3. Filters to keep only prose sentences (min 40 chars)
 * 
 * Benefits:
 * - Token efficient: plain text instead of HTML
 * - Naturally excludes tables: cell fragments are too short
 * - Multilingual: ICU4J handles Chinese, Japanese, Arabic, etc.
 */
class HtmlPreviewService : IHtmlPreviewService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        /**
         * Minimum sentence length to keep.
         * Table cells are typically < 40 chars.
         * Prose sentences are typically > 40 chars.
         */
        private const val MIN_SENTENCE_LENGTH = 40
        
        // Pre-compiled patterns for HTML stripping and metadata extraction
        
        // Title extraction
        private val TITLE_PATTERN: Pattern = Pattern.compile(
            "<title[^>]*>([^<]*)</title>",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )
        
        // Meta description extraction
        private val META_DESCRIPTION_PATTERN: Pattern = Pattern.compile(
            """<meta[^>]+name\s*=\s*["']description["'][^>]+content\s*=\s*["']([^"']+)["'][^>]*>|<meta[^>]+content\s*=\s*["']([^"']+)["'][^>]+name\s*=\s*["']description["'][^>]*>""",
            Pattern.CASE_INSENSITIVE
        )
        
        // Block elements that should be removed entirely (with content)
        private val REMOVE_BLOCK_PATTERNS = listOf(
            Pattern.compile("<script[^>]*>[\\s\\S]*?</script>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<style[^>]*>[\\s\\S]*?</style>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<noscript[^>]*>[\\s\\S]*?</noscript>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<svg[^>]*>[\\s\\S]*?</svg>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<iframe[^>]*>[\\s\\S]*?</iframe>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<template[^>]*>[\\s\\S]*?</template>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<!--[\\s\\S]*?-->", Pattern.DOTALL),
            // Navigation/chrome elements
            Pattern.compile("<nav[^>]*>[\\s\\S]*?</nav>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<header[^>]*>[\\s\\S]*?</header>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<footer[^>]*>[\\s\\S]*?</footer>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<aside[^>]*>[\\s\\S]*?</aside>", Pattern.CASE_INSENSITIVE),
            // Form elements
            Pattern.compile("<form[^>]*>[\\s\\S]*?</form>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<select[^>]*>[\\s\\S]*?</select>", Pattern.CASE_INSENSITIVE),
            // Code blocks
            Pattern.compile("<pre[^>]*>[\\s\\S]*?</pre>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<code[^>]*>[\\s\\S]*?</code>", Pattern.CASE_INSENSITIVE),
        )
        
        // Pattern to strip all remaining HTML tags
        private val HTML_TAG_PATTERN: Pattern = Pattern.compile("<[^>]+>")
        
        // Pattern to decode common HTML entities
        private val HTML_ENTITIES = mapOf(
            "&nbsp;" to " ",
            "&amp;" to "&",
            "&lt;" to "<",
            "&gt;" to ">",
            "&quot;" to "\"",
            "&apos;" to "'",
            "&#39;" to "'",
            "&#x27;" to "'",
            "&#34;" to "\"",
            "&#x22;" to "\""
        )
        
        // Pattern for numeric HTML entities
        private val NUMERIC_ENTITY_PATTERN: Pattern = Pattern.compile("&#(\\d+);")
        private val HEX_ENTITY_PATTERN: Pattern = Pattern.compile("&#x([0-9a-fA-F]+);")
        
        // Clean up whitespace
        private val MULTIPLE_WHITESPACE_PATTERN: Pattern = Pattern.compile("\\s+")
        private val MULTIPLE_NEWLINES_PATTERN: Pattern = Pattern.compile("\n{2,}")
    }

    override fun prepareHtmlPreview(html: String, url: String): HtmlPreviewResult {
        val totalStart = System.currentTimeMillis()
        
        // Step 1: Extract metadata before stripping
        val title = extractTitle(html)
        val description = extractDescription(html)
        
        // Step 2: Convert HTML to plain text
        val plainText = htmlToPlainText(html)
        
        // Step 3: Extract sentences using ICU4J
        val sentences = extractSentences(plainText)

        // Step 4: Join sentences
        val extractedText = sentences.joinToString(" ")
        
        val totalTime = System.currentTimeMillis() - totalStart
        
        logger.debug(
            "HTML preview for {}: {} chars -> {} sentences -> {} chars in {}ms",
            url, html.length, sentences.size, extractedText.length, totalTime
        )
        
        return HtmlPreviewResult(
            extractedSentences = extractedText,
            title = title,
            description = description
        )
    }
    
    /**
     * Extract title from HTML.
     */
    private fun extractTitle(html: String): String? {
        val matcher = TITLE_PATTERN.matcher(html)
        return if (matcher.find()) {
            decodeHtmlEntities(matcher.group(1)?.trim() ?: "").takeIf { it.isNotBlank() }
        } else null
    }
    
    /**
     * Extract meta description from HTML.
     */
    private fun extractDescription(html: String): String? {
        val matcher = META_DESCRIPTION_PATTERN.matcher(html)
        return if (matcher.find()) {
            val content = matcher.group(1) ?: matcher.group(2)
            decodeHtmlEntities(content?.trim() ?: "").takeIf { it.isNotBlank() }
        } else null
    }
    
    /**
     * Convert HTML to plain text by:
     * 1. Removing script, style, navigation blocks
     * 2. Stripping all HTML tags
     * 3. Decoding HTML entities
     * 4. Normalizing whitespace
     */
    private fun htmlToPlainText(html: String): String {
        var result = html
        
        // Remove block elements that shouldn't contribute text
        for (pattern in REMOVE_BLOCK_PATTERNS) {
            result = pattern.matcher(result).replaceAll(" ")
        }
        
        // Strip all remaining HTML tags
        result = HTML_TAG_PATTERN.matcher(result).replaceAll(" ")
        
        // Decode HTML entities
        result = decodeHtmlEntities(result)
        
        // Normalize whitespace
        result = MULTIPLE_WHITESPACE_PATTERN.matcher(result).replaceAll(" ")
        result = MULTIPLE_NEWLINES_PATTERN.matcher(result).replaceAll("\n")
        
        return result.trim()
    }
    
    /**
     * Decode HTML entities to their character equivalents.
     */
    private fun decodeHtmlEntities(text: String): String {
        var result = text
        
        // Named entities
        for ((entity, char) in HTML_ENTITIES) {
            result = result.replace(entity, char, ignoreCase = true)
        }
        
        // Numeric entities (&#123;)
        val numericMatcher = NUMERIC_ENTITY_PATTERN.matcher(result)
        val numericBuffer = StringBuffer()
        while (numericMatcher.find()) {
            val codePoint = numericMatcher.group(1).toIntOrNull()
            val replacement = if (codePoint != null && codePoint in 0..0x10FFFF) {
                String(Character.toChars(codePoint))
            } else {
                numericMatcher.group(0)
            }
            numericMatcher.appendReplacement(numericBuffer, Regex.escapeReplacement(replacement))
        }
        numericMatcher.appendTail(numericBuffer)
        result = numericBuffer.toString()
        
        // Hex entities (&#x1F4A9;)
        val hexMatcher = HEX_ENTITY_PATTERN.matcher(result)
        val hexBuffer = StringBuffer()
        while (hexMatcher.find()) {
            val codePoint = hexMatcher.group(1).toIntOrNull(16)
            val replacement = if (codePoint != null && codePoint in 0..0x10FFFF) {
                String(Character.toChars(codePoint))
            } else {
                hexMatcher.group(0)
            }
            hexMatcher.appendReplacement(hexBuffer, Regex.escapeReplacement(replacement))
        }
        hexMatcher.appendTail(hexBuffer)
        result = hexBuffer.toString()
        
        return result
    }
    
    /**
     * Extract sentences from text using ICU4J BreakIterator.
     * Works across languages (Chinese, Japanese, Arabic, etc.)
     */
    private fun extractSentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        
        // Use ULocale.ROOT for language-agnostic sentence breaking
        // ICU4J follows Unicode UAX #29 rules which work across languages
        val breakIterator = BreakIterator.getSentenceInstance(ULocale.ROOT)
        breakIterator.setText(text)
        
        val sentences = mutableListOf<String>()
        var start = breakIterator.first()
        var end = breakIterator.next()
        
        while (end != BreakIterator.DONE) {
            val sentence = text.substring(start, end).trim()
            if (sentence.isNotBlank()) {
                sentences.add(sentence)
            }
            start = end
            end = breakIterator.next()
        }
        
        return sentences
    }
}
