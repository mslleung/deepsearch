package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.valueobjects.CachedWebsiteContext
import io.deepsearch.domain.repositories.IWebsiteContextRepository
import io.deepsearch.infrastructure.database.WebsiteContextTable
import io.deepsearch.infrastructure.services.ITransactionService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.upsert

/**
 * Exposed implementation of IWebsiteContextRepository.
 * Caches website context per URL for faster query processing on subsequent queries.
 */
@OptIn(ExperimentalTime::class)
class ExposedWebsiteContextRepository(
    private val websiteContextTable: WebsiteContextTable,
    private val transactionService: ITransactionService
) : IWebsiteContextRepository {

    override suspend fun findByUrl(url: String): CachedWebsiteContext? = transactionService.withTransaction {
        websiteContextTable.selectAll()
            .where { websiteContextTable.url eq url }
            .map { mapRowToCachedWebsiteContext(it) }
            .singleOrNull()
    }

    override suspend fun upsert(context: CachedWebsiteContext): Unit = transactionService.withTransaction {
        websiteContextTable.upsert(
            keys = arrayOf(websiteContextTable.url)
        ) {
            it[url] = context.url
            it[contentSummary] = context.contentSummary
            it[cachedAtEpochMs] = context.cachedAt.toEpochMilliseconds()
        }
    }

    private fun mapRowToCachedWebsiteContext(row: ResultRow): CachedWebsiteContext {
        return CachedWebsiteContext(
            url = row[websiteContextTable.url],
            contentSummary = row[websiteContextTable.contentSummary],
            cachedAt = Instant.fromEpochMilliseconds(row[websiteContextTable.cachedAtEpochMs])
        )
    }
}

