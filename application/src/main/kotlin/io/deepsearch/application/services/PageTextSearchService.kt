package io.deepsearch.application.services

import com.ibm.icu.lang.UScript
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.cjk.CJKAnalyzer
import org.apache.lucene.analysis.de.GermanAnalyzer
import org.apache.lucene.analysis.en.EnglishAnalyzer
import org.apache.lucene.analysis.es.SpanishAnalyzer
import org.apache.lucene.analysis.fr.FrenchAnalyzer
import org.apache.lucene.analysis.it.ItalianAnalyzer
import org.apache.lucene.analysis.pt.PortugueseAnalyzer
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.ByteBuffersDirectory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

data class TextMatch(
    val matchedText: String,
    val surroundingContext: String,
    val score: Float
)

interface IPageTextSearchService {
    fun search(pageText: String, keywords: List<String>): Map<String, List<TextMatch>>
}

/**
 * Lucene-based text search with multilingual stemming.
 *
 * Builds a throwaway in-memory Lucene index per call, choosing a language-specific
 * analyzer (via ICU4J script/language detection) for stemming. Complements the
 * browser-side exact `countTextMatches` by catching morphological variations
 * (e.g. "pricing" matches "prices", "running" matches "runs").
 */
class PageTextSearchService : IPageTextSearchService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val MAX_RESULTS_PER_KEYWORD = 5
        private const val CONTEXT_RADIUS_CHARS = 60
        private const val LANGUAGE_SAMPLE_SIZE = 2000
        private const val MIN_WORDS_FOR_DETECTION = 10
        private const val MIN_MARKER_HITS = 3
    }

    override fun search(pageText: String, keywords: List<String>): Map<String, List<TextMatch>> {
        if (pageText.isBlank() || keywords.isEmpty()) return emptyMap()

        val analyzer = chooseAnalyzer(pageText)
        val lines = pageText.lines()

        return try {
            buildIndexAndSearch(lines, keywords, analyzer)
        } catch (e: Exception) {
            logger.warn("Lucene page text search failed: {}", e.message)
            emptyMap()
        } finally {
            analyzer.close()
        }
    }

    private fun buildIndexAndSearch(
        lines: List<String>,
        keywords: List<String>,
        analyzer: Analyzer
    ): Map<String, List<TextMatch>> {
        val directory = ByteBuffersDirectory()

        IndexWriter(directory, IndexWriterConfig(analyzer)).use { writer ->
            for ((idx, line) in lines.withIndex()) {
                if (line.isBlank()) continue
                val doc = Document()
                doc.add(TextField("content", line, Field.Store.YES))
                doc.add(StoredField("lineIndex", idx))
                writer.addDocument(doc)
            }
        }

        val reader = DirectoryReader.open(directory)
        try {
            val searcher = IndexSearcher(reader)
            val storedFields = reader.storedFields()
            val results = mutableMapOf<String, List<TextMatch>>()

            for (keyword in keywords) {
                val query = buildQueryForKeyword(keyword, analyzer) ?: continue
                val topDocs = searcher.search(query, MAX_RESULTS_PER_KEYWORD)

                val matches = topDocs.scoreDocs.map { scoreDoc ->
                    val doc = storedFields.document(scoreDoc.doc)
                    val matchedLine = doc.get("content")
                    val lineIndex = doc.getField("lineIndex")?.numericValue()?.toInt() ?: 0
                    TextMatch(
                        matchedText = matchedLine,
                        surroundingContext = buildSurroundingContext(lines, lineIndex),
                        score = scoreDoc.score
                    )
                }

                if (matches.isNotEmpty()) {
                    results[keyword] = matches
                }
            }

            return results
        } finally {
            reader.close()
            directory.close()
        }
    }

    private fun buildQueryForKeyword(keyword: String, analyzer: Analyzer): BooleanQuery? {
        val analyzedTerms = tokenize(keyword, analyzer)
        if (analyzedTerms.isEmpty()) return null

        val builder = BooleanQuery.Builder()
        for (term in analyzedTerms) {
            builder.add(TermQuery(Term("content", term)), BooleanClause.Occur.SHOULD)
        }
        return builder.build()
    }

    private fun tokenize(text: String, analyzer: Analyzer): List<String> {
        val terms = mutableListOf<String>()
        analyzer.tokenStream("content", text).use { stream ->
            val termAttr = stream.addAttribute(CharTermAttribute::class.java)
            stream.reset()
            while (stream.incrementToken()) {
                terms.add(termAttr.toString())
            }
            stream.end()
        }
        return terms
    }

    private fun buildSurroundingContext(lines: List<String>, centerLineIndex: Int): String {
        val start = (centerLineIndex - 1).coerceAtLeast(0)
        val end = (centerLineIndex + 1).coerceAtMost(lines.size - 1)
        val joined = (start..end).joinToString(" ") { lines[it].trim() }
        return if (joined.length > CONTEXT_RADIUS_CHARS * 2) {
            "...${joined.take(CONTEXT_RADIUS_CHARS * 2)}..."
        } else {
            joined
        }
    }

    internal fun chooseAnalyzer(text: String): Analyzer {
        val sample = text.take(LANGUAGE_SAMPLE_SIZE)
        val scriptCounts = mutableMapOf<Int, Int>()

        sample.codePoints().forEach { cp ->
            val script = UScript.getScript(cp)
            if (script != UScript.COMMON && script != UScript.INHERITED) {
                scriptCounts.merge(script, 1, Int::plus)
            }
        }

        val dominantScript = scriptCounts.maxByOrNull { it.value }?.key

        return when (dominantScript) {
            UScript.HAN, UScript.HIRAGANA, UScript.KATAKANA, UScript.HANGUL -> {
                logger.debug("Detected CJK script, using CJKAnalyzer")
                CJKAnalyzer()
            }
            UScript.LATIN -> {
                val language = detectLatinLanguage(sample)
                logger.debug("Detected Latin script, language hint={}", language)
                analyzerForLanguage(language)
            }
            else -> {
                logger.debug("Using StandardAnalyzer for script code {}", dominantScript)
                StandardAnalyzer()
            }
        }
    }

    private fun detectLatinLanguage(text: String): String {
        val words = text.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        if (words.size < MIN_WORDS_FOR_DETECTION) return "en"

        val wordSet = words.toHashSet()

        val languageMarkers = mapOf(
            "de" to setOf("und", "der", "die", "das", "ist", "nicht", "ein", "eine", "für", "mit", "auch", "auf"),
            "fr" to setOf("est", "les", "des", "une", "pour", "dans", "pas", "avec", "sur", "sont", "cette", "nous"),
            "es" to setOf("los", "las", "del", "una", "por", "con", "más", "como", "pero", "para", "son", "este"),
            "pt" to setOf("não", "uma", "dos", "para", "com", "mais", "como", "mas", "por", "são", "tem", "isso"),
            "it" to setOf("che", "non", "una", "per", "con", "sono", "più", "anche", "della", "nel", "dal", "gli"),
        )

        val best = languageMarkers.maxByOrNull { (_, markers) -> markers.count { it in wordSet } }
        return if (best != null && best.value.count { it in wordSet } >= MIN_MARKER_HITS) best.key else "en"
    }

    private fun analyzerForLanguage(language: String): Analyzer = when (language) {
        "de" -> GermanAnalyzer()
        "fr" -> FrenchAnalyzer()
        "es" -> SpanishAnalyzer()
        "pt" -> PortugueseAnalyzer()
        "it" -> ItalianAnalyzer()
        else -> EnglishAnalyzer()
    }
}
