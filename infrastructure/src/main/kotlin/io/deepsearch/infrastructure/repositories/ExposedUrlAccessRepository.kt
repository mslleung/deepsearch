package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.valueobjects.*
import io.deepsearch.domain.repositories.IUrlAccessRepository
import io.deepsearch.infrastructure.database.UrlAccessTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.time.ExperimentalTime

/**
 * Exposed-based implementation of IUrlAccessRepository.
 * Manages persistence of UrlAccess aggregate roots.
 */
@OptIn(ExperimentalTime::class)
class ExposedUrlAccessRepository(
    private val urlAccessTable: UrlAccessTable
) : IUrlAccessRepository {

    override suspend fun save(urlAccess: UrlAccess, querySessionId: String): UrlAccess = suspendTransaction {
        urlAccessTable.insert {
            it[urlAccessTable.querySessionId] = querySessionId
            it[url] = urlAccess.url
            it[timestampEpochMs] = urlAccess.timestamp.toEpochMilliseconds()
            
            when (urlAccess) {
                is CachedUrlAccess -> {
                    it[status] = "CACHED"
                    it[failureReason] = null
                }
                is UncachedUrlAccess -> {
                    it[status] = "UNCACHED"
                    it[failureReason] = null
                }
                is FailedUrlAccess -> {
                    it[status] = "FAILED"
                    it[failureReason] = urlAccess.reason.name
                }
            }
        }
        urlAccess
    }

    override suspend fun findByQuerySessionId(querySessionId: String): List<UrlAccess> = suspendTransaction {
        urlAccessTable.selectAll()
            .where { urlAccessTable.querySessionId eq querySessionId }
            .map { mapRowToUrlAccess(it) }
            .toList()
    }

    override suspend fun countByQuerySessionId(querySessionId: String): Int = suspendTransaction {
        urlAccessTable.selectAll()
            .where { urlAccessTable.querySessionId eq querySessionId }
            .count()
            .toInt()
    }

    override suspend fun existsByQuerySessionIdAndUrl(querySessionId: String, url: String): Boolean = suspendTransaction {
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
            "CACHED" -> CachedUrlAccess(url, timestamp)
            "UNCACHED" -> UncachedUrlAccess(url, timestamp)
            "FAILED" -> {
                val reasonName = row[urlAccessTable.failureReason]
                    ?: throw IllegalStateException("FAILED status must have a failure reason")
                val reason = UrlFailureReason.valueOf(reasonName)
                FailedUrlAccess(url, timestamp, reason)
            }
            else -> throw IllegalStateException("Unknown URL access status: $status")
        }
    }
}

