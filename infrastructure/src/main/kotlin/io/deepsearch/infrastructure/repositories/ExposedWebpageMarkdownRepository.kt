package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.exceptions.OptimisticLockException
import io.deepsearch.domain.models.entities.WebpageMarkdown
import io.deepsearch.domain.repositories.IWebpageMarkdownRepository
import io.deepsearch.infrastructure.database.WebpageMarkdownCacheTable
import io.deepsearch.infrastructure.services.ITransactionService
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.upsert
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedWebpageMarkdownRepository(
    private val webpageMarkdownTable: WebpageMarkdownCacheTable,
    private val transactionService: ITransactionService
) : IWebpageMarkdownRepository {

    private val logger: Logger = LoggerFactory.getLogger(ExposedWebpageMarkdownRepository::class.java)

    /**
     * Columns to select when mapping to WebpageMarkdown.
     * Excludes markdownSearchVector to avoid R2DBC decoding issues with tsvector.
     * Excludes markdownSanitized as it's only used internally for full-text search.
     */
    private val webpageMarkdownColumns: List<Column<*>>
        get() = listOf(
            webpageMarkdownTable.url,
            webpageMarkdownTable.title,
            webpageMarkdownTable.description,
            webpageMarkdownTable.markdown,
            webpageMarkdownTable.html,
            webpageMarkdownTable.httpStatus,
            webpageMarkdownTable.httpReason,
            webpageMarkdownTable.mimeType,
            webpageMarkdownTable.embedding,
            webpageMarkdownTable.createdAtEpochMs,
            webpageMarkdownTable.updatedAtEpochMs,
            webpageMarkdownTable.version
        )

    override suspend fun findByUrl(url: String): WebpageMarkdown? = transactionService.withTransaction {
        webpageMarkdownTable
            .select(webpageMarkdownColumns)
            .where { webpageMarkdownTable.url eq url }
            .map { mapRowToWebpageMarkdown(it) }
            .singleOrNull()
    }

    override suspend fun upsert(webpage: WebpageMarkdown): Unit = transactionService.withTransaction {
        // Check for existing version to implement optimistic locking
        val existingVersion = webpageMarkdownTable
            .select(webpageMarkdownTable.version)
            .where { webpageMarkdownTable.url eq webpage.url }
            .map { it[webpageMarkdownTable.version] }
            .singleOrNull()
        
        // If record exists, verify version matches to prevent concurrent modification issues
        if (existingVersion != null && existingVersion != webpage.version) {
            throw OptimisticLockException("WebpageMarkdown", webpage.url, webpage.version)
        }
        
        // Calculate new version (increment from existing, or use provided version for new records)
        val newVersion = if (existingVersion != null) existingVersion + 1 else webpage.version
        
        // Sanitize markdown for full-text search (removes problematic Unicode that breaks R2DBC tsvector decoding)
        // Stored separately so original markdown with emoji/special chars is preserved
        val sanitizedMarkdown = webpage.markdown?.let { sanitizeForFullTextSearch(it) }
        
        // Upsert webpage markdown record with incremented version
        webpageMarkdownTable.upsert(
            keys = arrayOf(webpageMarkdownTable.url)
        ) {
            it[url] = webpage.url
            it[title] = webpage.title
            it[description] = webpage.description
            it[markdown] = webpage.markdown  // Original markdown preserved
            it[markdownSanitized] = sanitizedMarkdown  // Sanitized version for tsvector trigger
            it[html] = webpage.html
            it[httpStatus] = webpage.httpStatus
            it[httpReason] = webpage.httpReason
            it[mimeType] = webpage.mimeType
            it[embedding] = webpage.embedding
            it[createdAtEpochMs] = webpage.createdAt.toEpochMilliseconds()
            it[updatedAtEpochMs] = webpage.updatedAt.toEpochMilliseconds()
            it[version] = newVersion
        }
        
        // Update the webpage object's version to reflect the new version
        webpage.version = newVersion
        
        logger.debug("Upserted webpage for URL: {} (version: {} -> {})", webpage.url, existingVersion ?: "new", newVersion)
    }

    override suspend fun listByDomainPrefix(prefix: String, offset: Int, limit: Int): List<WebpageMarkdown> = transactionService.withTransaction {
        webpageMarkdownTable
            .select(webpageMarkdownColumns)
            .where { webpageMarkdownTable.url like ("$prefix%") }
            .limit(limit)
            .offset(offset.toLong())
            .map { mapRowToWebpageMarkdown(it) }
            .toList()
    }

    override suspend fun countByDomainPrefix(prefix: String): Long = transactionService.withTransaction {
        webpageMarkdownTable.selectAll()
            .where { webpageMarkdownTable.url like ("$prefix%") }
            .count()
    }

    override suspend fun searchByUrl(query: String, offset: Int, limit: Int): List<WebpageMarkdown> = transactionService.withTransaction {
        val pattern = "%${query.replace("%", "\\%").replace("_", "\\_")}%"
        webpageMarkdownTable
            .select(webpageMarkdownColumns)
            .where { webpageMarkdownTable.url like pattern }
            .limit(limit)
            .offset(offset.toLong())
            .map { mapRowToWebpageMarkdown(it) }
            .toList()
    }

    override suspend fun countSearchByUrl(query: String): Long = transactionService.withTransaction {
        val pattern = "%${query.replace("%", "\\%").replace("_", "\\_")}%"
        webpageMarkdownTable.selectAll()
            .where { webpageMarkdownTable.url like pattern }
            .count()
    }

    override suspend fun searchHybrid(
        textQuery: String,
        queryEmbedding: List<Float>,
        urlPrefix: String,
        minUpdatedAtEpochMs: Long?,
        limit: Int
    ): List<WebpageMarkdown> {
        val startTime = Clock.System.now()
        
        // Perform keyword and semantic searches in parallel
        val (keywordResults, semanticResults) = coroutineScope {
            val keywordDeferred = async { performKeywordSearch(textQuery, urlPrefix, minUpdatedAtEpochMs, limit) }
            val semanticDeferred = async { performSemanticSearch(queryEmbedding, urlPrefix, minUpdatedAtEpochMs, limit) }

            Pair(keywordDeferred.await(), semanticDeferred.await())
        }

        // Track URLs from each source
        val keywordUrls = keywordResults.map { it.url }.toSet()
        val semanticUrls = semanticResults.map { it.url }.toSet()
        
        // Log search results
        logger.debug("Keyword search returned {} results: {}", keywordResults.size, keywordUrls)
        logger.debug("Semantic search returned {} results: {}", semanticResults.size, semanticUrls)
        
        // Combine results using Reciprocal Rank Fusion (RRF)
        // Reference: https://github.com/pgvector/pgvector?tab=readme-ov-file#hybrid-search
        // RRF Formula: score = 1 / (k + rank), where k=60 is standard constant
        val rrfK = 60
        val rrfScores = mutableMapOf<String, Double>()
        
        // Add scores from keyword search
        keywordResults.forEachIndexed { index, webpage ->
            val rank = index + 1
            val score = 1.0 / (rrfK + rank)
            rrfScores[webpage.url] = rrfScores.getOrDefault(webpage.url, 0.0) + score
        }
        
        // Add scores from semantic search
        semanticResults.forEachIndexed { index, webpage ->
            val rank = index + 1
            val score = 1.0 / (rrfK + rank)
            rrfScores[webpage.url] = rrfScores.getOrDefault(webpage.url, 0.0) + score
        }
        
        // Combine all unique webpages
        val allWebpages = (keywordResults + semanticResults).associateBy { it.url }
        
        // Sort by RRF score and return top results
        val finalResults = rrfScores.entries
            .sortedByDescending { it.value }
            .take(limit)
            .mapNotNull { allWebpages[it.key] }
        
        // Log each final result with its source
        finalResults.forEachIndexed { index, webpage ->
            val source = when {
                keywordUrls.contains(webpage.url) && semanticUrls.contains(webpage.url) -> "both (keyword + semantic)"
                keywordUrls.contains(webpage.url) -> "keyword only"
                semanticUrls.contains(webpage.url) -> "semantic only"
                else -> "unknown"
            }
            logger.debug("Final result #{}: {} (source: {})", index + 1, webpage.url, source)
        }
        
        // Log summary
        val duration = Clock.System.now() - startTime
        logger.info("Hybrid search completed in {}ms, returned {} results", duration.inWholeMilliseconds, finalResults.size)
        
        return finalResults
    }
    
    /**
     * Perform keyword-based full-text search on markdown content.
     * Uses raw SQL via custom expressions to properly implement PostgreSQL full-text search operators.
     * Reference: https://github.com/pgvector/pgvector?tab=readme-ov-file#hybrid-search
     */
    private suspend fun performKeywordSearch(
        textQuery: String,
        urlPrefix: String,
        minUpdatedAtEpochMs: Long?,
        limit: Int
    ): List<WebpageMarkdown> = transactionService.withTransaction {
        // Sanitize and limit query to prevent tsquery buffer overflow issues
        // PostgreSQL websearch_to_tsquery can generate very large tsquery expressions for long queries
        val sanitizedQuery = sanitizeSearchQuery(textQuery)
        
        // Log if query was truncated
        if (sanitizedQuery != textQuery) {
            logger.warn(
                "Search query was sanitized/truncated (original length: {}, sanitized length: {}). " +
                "Original: '{}...', Sanitized: '{}'",
                textQuery.length,
                sanitizedQuery.length,
                textQuery.take(100),
                sanitizedQuery
            )
        }
        
        // Escape single quotes for SQL safety
        val escapedQuery = sanitizedQuery.replace("'", "''")
        
        // Create custom SQL expression for full-text search match using @@ operator
        // This ensures documents that don't match the query are filtered out
        val tsQueryExpr = "websearch_to_tsquery('english', '$escapedQuery')"
        
        val tsMatchExpr = object : Expression<Boolean>() {
            override fun toQueryBuilder(queryBuilder: QueryBuilder) {
                queryBuilder.append("(${webpageMarkdownTable.tableName}.markdown_search_vector @@ $tsQueryExpr)")
            }
        }
        
        // Create custom SQL expression for ts_rank to sort by relevance
        val tsRankExpr = object : Expression<Double>() {
            override fun toQueryBuilder(queryBuilder: QueryBuilder) {
                queryBuilder.append("ts_rank(${webpageMarkdownTable.tableName}.markdown_search_vector, $tsQueryExpr)")
            }
        }
        
        // Build query with WHERE conditions - filter for documents matching the full-text search query
        // Use select() with specific columns to avoid R2DBC issues with tsvector decoding
        webpageMarkdownTable.select(webpageMarkdownColumns)
            .where {
                val urlCondition = webpageMarkdownTable.url like "$urlPrefix%"
                val markdownCondition = webpageMarkdownTable.markdown.isNotNull()
                
                if (minUpdatedAtEpochMs != null) {
                    urlCondition and (webpageMarkdownTable.updatedAtEpochMs greaterEq minUpdatedAtEpochMs) and markdownCondition and tsMatchExpr
                } else {
                    urlCondition and markdownCondition and tsMatchExpr
                }
            }
            .orderBy(tsRankExpr to SortOrder.DESC)
            .limit(limit)
            .map { mapRowToWebpageMarkdown(it) }
            .toList()
    }
    
    /**
     * Perform semantic vector similarity search
     */
    private suspend fun performSemanticSearch(
        queryEmbedding: List<Float>,
        urlPrefix: String,
        minUpdatedAtEpochMs: Long?,
        limit: Int
    ): List<WebpageMarkdown> = transactionService.withTransaction {
        // Configure HNSW iterative index scan for better recall with filters
        exec("""
            SET LOCAL hnsw.iterative_scan = 'strict_order';
            SET LOCAL hnsw.max_scan_tuples = 20000;
        """.trimIndent())
        
        // Format query embedding as pgvector string: '[1.0,2.0,3.0,...]'
        val embeddingStr = "[${queryEmbedding.joinToString(",")}]"
        
        // Create custom SQL expression for cosine distance using pgvector's <=> operator
        val cosineDistanceExpr = object : Expression<Double>() {
            override fun toQueryBuilder(queryBuilder: QueryBuilder) {
                queryBuilder.append("(${webpageMarkdownTable.tableName}.embedding <=> '$embeddingStr'::vector)")
            }
        }
        
        // Build query filtering for documents with embeddings
        // Use select() with specific columns to avoid R2DBC issues with tsvector decoding
        webpageMarkdownTable
            .select(webpageMarkdownColumns)
            .where {
                val urlCondition = webpageMarkdownTable.url like "$urlPrefix%"
                val markdownCondition = webpageMarkdownTable.markdown.isNotNull()
                val embeddingCondition = webpageMarkdownTable.embedding.isNotNull()
                
                if (minUpdatedAtEpochMs != null) {
                    urlCondition and (webpageMarkdownTable.updatedAtEpochMs greaterEq minUpdatedAtEpochMs) and markdownCondition and embeddingCondition
                } else {
                    urlCondition and markdownCondition and embeddingCondition
                }
            }
            .orderBy(cosineDistanceExpr to SortOrder.ASC)  // Order by cosine distance (ascending = most similar)
            .limit(limit)
            .map { mapRowToWebpageMarkdown(it) }
            .toList()
    }

    private fun mapRowToWebpageMarkdown(row: ResultRow): WebpageMarkdown {
        return WebpageMarkdown(
            url = row[webpageMarkdownTable.url],
            title = row[webpageMarkdownTable.title],
            description = row[webpageMarkdownTable.description],
            markdown = row[webpageMarkdownTable.markdown],
            html = row[webpageMarkdownTable.html],
            httpStatus = row[webpageMarkdownTable.httpStatus],
            httpReason = row[webpageMarkdownTable.httpReason],
            mimeType = row[webpageMarkdownTable.mimeType],
            embedding = row[webpageMarkdownTable.embedding],
            createdAt = Instant.fromEpochMilliseconds(row[webpageMarkdownTable.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[webpageMarkdownTable.updatedAtEpochMs]),
            version = row[webpageMarkdownTable.version]
        )
    }

    companion object {
        /**
         * Maximum query length for full-text search to prevent tsquery buffer overflow.
         * PostgreSQL websearch_to_tsquery can generate very large tsquery expressions
         * that exceed R2DBC buffer limits (>100KB), causing IndexOutOfBoundsException.
         */
        private const val MAX_SEARCH_QUERY_LENGTH = 500
        
        /**
         * Sanitizes and limits search query for PostgreSQL websearch_to_tsquery.
         * Prevents buffer overflow issues by:
         * - Limiting query length to MAX_SEARCH_QUERY_LENGTH characters
         * - Removing problematic Unicode characters (emoji, special symbols)
         * - Collapsing multiple spaces
         * - Trimming whitespace
         */
        fun sanitizeSearchQuery(query: String): String {
            return query
                .take(MAX_SEARCH_QUERY_LENGTH)              // Limit length to prevent huge tsquery expressions
                .replace(Regex("[\\p{So}\\p{Sk}]"), " ")    // Remove emoji and pictographs
                .replace(Regex("\\p{Cf}"), "")               // Remove format characters
                .replace(Regex("[\\p{C}]"), "")              // Remove other control characters
                .replace(Regex("\\s+"), " ")                 // Collapse multiple whitespace
                .trim()
        }
        
        /**
         * Sanitizes text for PostgreSQL full-text search (tsvector).
         * Removes Unicode characters that can cause R2DBC decoding issues:
         * - Emoji and pictographs (Symbol, Other category)
         * - Modifier symbols (skin tones, etc.)
         * - Format characters (zero-width joiners, etc.)
         */
        fun sanitizeForFullTextSearch(text: String): String {
            return text
                .replace(Regex("[\\p{So}\\p{Sk}]"), " ")  // Symbol Other & Symbol Modifier (emoji, pictographs)
                .replace(Regex("\\p{Cf}"), "")             // Format characters (zero-width joiners, etc.)
                .replace(Regex("\\s+"), " ")              // Collapse multiple whitespace
                .trim()
        }
    }
}
