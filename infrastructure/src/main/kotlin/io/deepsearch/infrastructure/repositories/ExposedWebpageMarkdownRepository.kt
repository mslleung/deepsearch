package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.WebpageMarkdown
import io.deepsearch.domain.repositories.IWebpageMarkdownRepository
import io.deepsearch.infrastructure.database.WebpageMarkdownCacheTable
import io.deepsearch.infrastructure.services.ITransactionService
import io.deepsearch.infrastructure.services.TransactionService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.upsert
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedWebpageMarkdownRepository(
    private val webpageMarkdownTable: WebpageMarkdownCacheTable,
    private val transactionService: ITransactionService
) : IWebpageMarkdownRepository {

    override suspend fun findByUrl(url: String): WebpageMarkdown? = transactionService.withTransaction {
        webpageMarkdownTable.selectAll()
            .where { webpageMarkdownTable.url eq url }
            .map { mapRowToWebpageMarkdown(it) }
            .singleOrNull()
    }

    override suspend fun upsert(webpage: WebpageMarkdown): Unit = transactionService.withTransaction {
        webpageMarkdownTable.upsert(
            keys = arrayOf(webpageMarkdownTable.url)
        ) {
            it[url] = webpage.url
            it[markdown] = webpage.markdown
            it[html] = webpage.html
            it[httpStatus] = webpage.httpStatus
            it[httpReason] = webpage.httpReason
            it[mimeType] = webpage.mimeType
            it[embedding] = webpage.embedding
            it[createdAtEpochMs] = webpage.createdAt.toEpochMilliseconds()
            it[updatedAtEpochMs] = webpage.updatedAt.toEpochMilliseconds()
            it[version] = webpage.version
        }
    }

    override suspend fun listByDomainPrefix(prefix: String, offset: Int, limit: Int): List<WebpageMarkdown> = transactionService.withTransaction {
        webpageMarkdownTable.selectAll()
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
        webpageMarkdownTable.selectAll()
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

    override suspend fun searchSimilar(
        queryEmbedding: List<Float>,
        urlPrefix: String,
        minUpdatedAtEpochMs: Long?,
        limit: Int
    ): List<WebpageMarkdown> = transactionService.withTransaction {
        // Configure HNSW iterative index scan for better recall with filters
        // This ensures the index scans more candidates when our WHERE filters reduce results
        exec("""
            SET LOCAL hnsw.iterative_scan = 'strict_order';
            SET LOCAL hnsw.max_scan_tuples = 20000;
        """.trimIndent())
        
        // Use pgvector's native <=> operator for cosine distance
        // This leverages the HNSW index for efficient O(log n) similarity search
        
        // Format query embedding as pgvector string: '[1.0,2.0,3.0,...]'
        val embeddingStr = "[${queryEmbedding.joinToString(",")}]"
        
        // Create custom SQL expression for cosine distance using pgvector's <=> operator
        val cosineDistanceExpr = object : Expression<Double>() {
            override fun toQueryBuilder(queryBuilder: QueryBuilder) {
                queryBuilder.append("(embedding <=> '$embeddingStr'::vector)")
            }
        }
        
        // Build query with WHERE conditions
        val baseQuery = webpageMarkdownTable.selectAll()
            .where {
                val urlCondition = webpageMarkdownTable.url like "$urlPrefix%"
                val embeddingCondition = webpageMarkdownTable.embedding.isNotNull()
                
                if (minUpdatedAtEpochMs != null) {
                    urlCondition and (webpageMarkdownTable.updatedAtEpochMs greaterEq minUpdatedAtEpochMs) and embeddingCondition
                } else {
                    urlCondition and embeddingCondition
                }
            }
            .orderBy(cosineDistanceExpr to SortOrder.ASC)  // Order by cosine distance (ascending = most similar)
            .limit(limit)
        
        // Execute query and map results
        baseQuery
            .map { mapRowToWebpageMarkdown(it) }
            .toList()
        
        // Iterative index scans configured above improve recall when filters are applied
        // Can tune hnsw.max_scan_tuples and hnsw.scan_mem_multiplier if needed
        // Reference: https://github.com/pgvector/pgvector?tab=readme-ov-file#iterative-index-scans
    }

    private fun mapRowToWebpageMarkdown(row: ResultRow): WebpageMarkdown {
        return WebpageMarkdown(
            url = row[webpageMarkdownTable.url],
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
}
