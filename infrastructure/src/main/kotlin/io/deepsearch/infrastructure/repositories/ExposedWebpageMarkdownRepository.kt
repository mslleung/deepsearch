package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.WebpageMarkdown
import io.deepsearch.domain.repositories.IWebpageMarkdownRepository
import io.deepsearch.infrastructure.database.WebpageMarkdownCacheEmbeddingTable
import io.deepsearch.infrastructure.database.WebpageMarkdownCacheTable
import io.deepsearch.infrastructure.services.ITransactionService
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
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
    private val webpageMarkdownEmbeddingTable: WebpageMarkdownCacheEmbeddingTable,
    private val transactionService: ITransactionService
) : IWebpageMarkdownRepository {

    private val logger: Logger = LoggerFactory.getLogger(ExposedWebpageMarkdownRepository::class.java)

    override suspend fun findByUrl(url: String): WebpageMarkdown? = transactionService.withTransaction {
        webpageMarkdownTable
            .leftJoin(webpageMarkdownEmbeddingTable)
            .selectAll()
            .where { webpageMarkdownTable.url eq url }
            .map { mapRowToWebpageMarkdown(it) }
            .singleOrNull()
    }

    override suspend fun upsert(webpage: WebpageMarkdown): Unit = transactionService.withTransaction {
        // Upsert main webpage markdown record
        webpageMarkdownTable.upsert(
            keys = arrayOf(webpageMarkdownTable.url)
        ) {
            it[url] = webpage.url
            it[markdown] = webpage.markdown
            it[html] = webpage.html
            it[httpStatus] = webpage.httpStatus
            it[httpReason] = webpage.httpReason
            it[mimeType] = webpage.mimeType
            it[createdAtEpochMs] = webpage.createdAt.toEpochMilliseconds()
            it[updatedAtEpochMs] = webpage.updatedAt.toEpochMilliseconds()
            it[version] = webpage.version
        }
        
        // Upsert embedding if present
        if (webpage.embedding != null) {
            webpageMarkdownEmbeddingTable.upsert(
                keys = arrayOf(webpageMarkdownEmbeddingTable.url)
            ) {
                it[url] = webpage.url
                it[embedding] = webpage.embedding!!
                it[createdAtEpochMs] = webpage.createdAt.toEpochMilliseconds()
                it[updatedAtEpochMs] = webpage.updatedAt.toEpochMilliseconds()
                it[version] = webpage.version
            }
        }
    }

    override suspend fun listByDomainPrefix(prefix: String, offset: Int, limit: Int): List<WebpageMarkdown> = transactionService.withTransaction {
        webpageMarkdownTable
            .leftJoin(webpageMarkdownEmbeddingTable)
            .selectAll()
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
            .leftJoin(webpageMarkdownEmbeddingTable)
            .selectAll()
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
        // Escape single quotes for SQL safety
        val escapedQuery = textQuery.replace("'", "''")
        
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
        webpageMarkdownTable.selectAll()
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
        // Reference the embedding column from the embedding table
        val cosineDistanceExpr = object : Expression<Double>() {
            override fun toQueryBuilder(queryBuilder: QueryBuilder) {
                queryBuilder.append("(${webpageMarkdownEmbeddingTable.tableName}.embedding <=> '$embeddingStr'::vector)")
            }
        }
        
        // Build query with INNER JOIN (embeddings are required for semantic search)
        webpageMarkdownTable
            .innerJoin(webpageMarkdownEmbeddingTable)
            .selectAll()
            .where {
                val urlCondition = webpageMarkdownTable.url like "$urlPrefix%"
                val markdownCondition = webpageMarkdownTable.markdown.isNotNull()
                
                if (minUpdatedAtEpochMs != null) {
                    urlCondition and (webpageMarkdownTable.updatedAtEpochMs greaterEq minUpdatedAtEpochMs) and markdownCondition
                } else {
                    urlCondition and markdownCondition
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
            markdown = row[webpageMarkdownTable.markdown],
            html = row[webpageMarkdownTable.html],
            httpStatus = row[webpageMarkdownTable.httpStatus],
            httpReason = row[webpageMarkdownTable.httpReason],
            mimeType = row[webpageMarkdownTable.mimeType],
            embedding = row.getOrNull(webpageMarkdownEmbeddingTable.embedding),
            createdAt = Instant.fromEpochMilliseconds(row[webpageMarkdownTable.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[webpageMarkdownTable.updatedAtEpochMs]),
            version = row[webpageMarkdownTable.version]
        )
    }
}
