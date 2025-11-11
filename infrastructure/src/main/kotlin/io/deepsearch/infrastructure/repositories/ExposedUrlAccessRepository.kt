package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.valueobjects.*
import io.deepsearch.domain.repositories.IUrlAccessRepository
import io.deepsearch.infrastructure.database.UrlAccessTable
import io.deepsearch.infrastructure.services.ITransactionService
import io.deepsearch.infrastructure.services.TransactionService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import kotlin.time.ExperimentalTime

/**
 * Exposed-based implementation of IUrlAccessRepository.
 * Manages persistence of UrlAccess aggregate roots.
 */
@OptIn(ExperimentalTime::class)
class ExposedUrlAccessRepository(
    private val urlAccessTable: UrlAccessTable,
    private val transactionService: ITransactionService
) : IUrlAccessRepository {

    private enum class UrlAccessStatus {
        CACHED,
        UNCACHED,
        FAILED
    }

    override suspend fun save(urlAccess: UrlAccess, querySessionId: String): UrlAccess = transactionService.withTransaction {
        urlAccessTable.insert {
            it[urlAccessTable.querySessionId] = querySessionId
            it[url] = urlAccess.url
            it[timestampEpochMs] = urlAccess.timestamp.toEpochMilliseconds()
            
            when (urlAccess) {
                is CachedUrlAccess -> {
                    it[status] = UrlAccessStatus.CACHED.name
                    it[exceptionType] = null
                    it[exceptionMessage] = null
                }
                is UncachedUrlAccess -> {
                    it[status] = UrlAccessStatus.UNCACHED.name
                    it[exceptionType] = null
                    it[exceptionMessage] = null
                }
                is FailedUrlAccess -> {
                    it[status] = UrlAccessStatus.FAILED.name
                    it[exceptionType] = urlAccess.exceptionType
                    it[exceptionMessage] = urlAccess.message
                }
            }
        }
        urlAccess
    }

    override suspend fun findByQuerySessionId(querySessionId: String): List<UrlAccess> = transactionService.withTransaction {
        urlAccessTable.selectAll()
            .where { urlAccessTable.querySessionId eq querySessionId }
            .map { mapRowToUrlAccess(it) }
            .toList()
    }

    override suspend fun findCachedByQuerySessionId(querySessionId: String): List<CachedUrlAccess> = transactionService.withTransaction {
        urlAccessTable.selectAll()
            .where { 
                (urlAccessTable.querySessionId eq querySessionId) and 
                (urlAccessTable.status eq UrlAccessStatus.CACHED.name) 
            }
            .map { mapRowToUrlAccess(it) as CachedUrlAccess }
            .toList()
    }

    override suspend fun findUncachedByQuerySessionId(querySessionId: String): List<UncachedUrlAccess> = transactionService.withTransaction {
        urlAccessTable.selectAll()
            .where { 
                (urlAccessTable.querySessionId eq querySessionId) and 
                (urlAccessTable.status eq UrlAccessStatus.UNCACHED.name) 
            }
            .map { mapRowToUrlAccess(it) as UncachedUrlAccess }
            .toList()
    }

    override suspend fun findFailedByQuerySessionId(querySessionId: String): List<FailedUrlAccess> = transactionService.withTransaction {
        urlAccessTable.selectAll()
            .where { 
                (urlAccessTable.querySessionId eq querySessionId) and 
                (urlAccessTable.status eq UrlAccessStatus.FAILED.name) 
            }
            .map { mapRowToUrlAccess(it) as FailedUrlAccess }
            .toList()
    }

    override suspend fun countByQuerySessionId(querySessionId: String): Int = transactionService.withTransaction {
        urlAccessTable.selectAll()
            .where { urlAccessTable.querySessionId eq querySessionId }
            .count()
            .toInt()
    }

    override suspend fun existsByQuerySessionIdAndUrl(querySessionId: String, url: String): Boolean = transactionService.withTransaction {
        urlAccessTable.selectAll()
            .where { 
                (urlAccessTable.querySessionId eq querySessionId) and 
                (urlAccessTable.url eq url) 
            }
            .count() > 0
    }

    /**
     * Map a database row to a UrlAccess domain object (polymorphic mapping).
     */
    private fun mapRowToUrlAccess(row: ResultRow): UrlAccess {
        val url = row[urlAccessTable.url]
        val timestampMs = row[urlAccessTable.timestampEpochMs]
        val timestamp = kotlin.time.Instant.fromEpochMilliseconds(timestampMs)
        val status = row[urlAccessTable.status]

        return when (status) {
            UrlAccessStatus.CACHED.name -> CachedUrlAccess(url, timestamp)
            UrlAccessStatus.UNCACHED.name -> UncachedUrlAccess(url, timestamp)
            UrlAccessStatus.FAILED.name -> {
                val exceptionType = row[urlAccessTable.exceptionType]
                    ?: throw IllegalStateException("FAILED status must have an exception type")
                val exceptionMessage = row[urlAccessTable.exceptionMessage]
                    ?: throw IllegalStateException("FAILED status must have an exception message")
                FailedUrlAccess(url, timestamp, exceptionType, exceptionMessage)
            }
            else -> throw IllegalStateException("Unknown URL access status: $status")
        }
    }
}

