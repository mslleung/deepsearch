package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.WebpageMarkdown
import io.deepsearch.domain.repositories.IWebpageMarkdownRepository
import io.deepsearch.infrastructure.database.WebpageMarkdownTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.upsert
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedWebpageMarkdownRepository : IWebpageMarkdownRepository {

    override suspend fun findByUrl(url: String): WebpageMarkdown? = suspendTransaction {
        WebpageMarkdownTable.selectAll()
            .where { WebpageMarkdownTable.url eq url }
            .map { mapRowToWebpageMarkdown(it) }
            .singleOrNull()
    }

    override suspend fun upsert(webpage: WebpageMarkdown): Unit = suspendTransaction {
        WebpageMarkdownTable.upsert(
            keys = arrayOf(WebpageMarkdownTable.url)
        ) {
            it[url] = webpage.url
            it[markdown] = webpage.markdown
            it[html] = webpage.html
            it[httpStatus] = webpage.httpStatus
            it[httpReason] = webpage.httpReason
            it[mimeType] = webpage.mimeType
            it[createdAtEpochMs] = webpage.createdAt.toEpochMilliseconds()
            it[updatedAtEpochMs] = webpage.updatedAt.toEpochMilliseconds()
        }
    }

    override suspend fun listByDomainPrefix(prefix: String, offset: Int, limit: Int): List<WebpageMarkdown> = suspendTransaction {
        WebpageMarkdownTable.selectAll()
            .where { WebpageMarkdownTable.url like ("$prefix%") }
            .limit(limit)
            .offset(offset.toLong())
            .map { mapRowToWebpageMarkdown(it) }
            .toList()
    }

    override suspend fun countByDomainPrefix(prefix: String): Long = suspendTransaction {
        WebpageMarkdownTable.selectAll()
            .where { WebpageMarkdownTable.url like ("$prefix%") }
            .count()
    }

    override suspend fun searchByUrl(query: String, offset: Int, limit: Int): List<WebpageMarkdown> = suspendTransaction {
        val pattern = "%${query.replace("%", "\\%").replace("_", "\\_")}%"
        WebpageMarkdownTable.selectAll()
            .where { WebpageMarkdownTable.url like pattern }
            .limit(limit)
            .offset(offset.toLong())
            .map { mapRowToWebpageMarkdown(it) }
            .toList()
    }

    override suspend fun countSearchByUrl(query: String): Long = suspendTransaction {
        val pattern = "%${query.replace("%", "\\%").replace("_", "\\_")}%"
        WebpageMarkdownTable.selectAll()
            .where { WebpageMarkdownTable.url like pattern }
            .count()
    }

    private fun mapRowToWebpageMarkdown(row: ResultRow): WebpageMarkdown {
        return WebpageMarkdown(
            url = row[WebpageMarkdownTable.url],
            markdown = row[WebpageMarkdownTable.markdown],
            html = row[WebpageMarkdownTable.html],
            httpStatus = row[WebpageMarkdownTable.httpStatus],
            httpReason = row[WebpageMarkdownTable.httpReason],
            mimeType = row[WebpageMarkdownTable.mimeType],
            createdAt = Instant.fromEpochMilliseconds(row[WebpageMarkdownTable.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[WebpageMarkdownTable.updatedAtEpochMs])
        )
    }
}

