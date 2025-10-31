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
class ExposedWebpageMarkdownRepository(
    private val webpageMarkdownTable: WebpageMarkdownTable
) : IWebpageMarkdownRepository {

    override suspend fun findByUrl(url: String): WebpageMarkdown? = suspendTransaction {
        webpageMarkdownTable.selectAll()
            .where { webpageMarkdownTable.url eq url }
            .map { mapRowToWebpageMarkdown(it) }
            .singleOrNull()
    }

    override suspend fun upsert(webpage: WebpageMarkdown): Unit = suspendTransaction {
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
    }

    override suspend fun listByDomainPrefix(prefix: String, offset: Int, limit: Int): List<WebpageMarkdown> = suspendTransaction {
        webpageMarkdownTable.selectAll()
            .where { webpageMarkdownTable.url like ("$prefix%") }
            .limit(limit)
            .offset(offset.toLong())
            .map { mapRowToWebpageMarkdown(it) }
            .toList()
    }

    override suspend fun countByDomainPrefix(prefix: String): Long = suspendTransaction {
        webpageMarkdownTable.selectAll()
            .where { webpageMarkdownTable.url like ("$prefix%") }
            .count()
    }

    override suspend fun searchByUrl(query: String, offset: Int, limit: Int): List<WebpageMarkdown> = suspendTransaction {
        val pattern = "%${query.replace("%", "\\%").replace("_", "\\_")}%"
        webpageMarkdownTable.selectAll()
            .where { webpageMarkdownTable.url like pattern }
            .limit(limit)
            .offset(offset.toLong())
            .map { mapRowToWebpageMarkdown(it) }
            .toList()
    }

    override suspend fun countSearchByUrl(query: String): Long = suspendTransaction {
        val pattern = "%${query.replace("%", "\\%").replace("_", "\\_")}%"
        webpageMarkdownTable.selectAll()
            .where { webpageMarkdownTable.url like pattern }
            .count()
    }

    private fun mapRowToWebpageMarkdown(row: ResultRow): WebpageMarkdown {
        return WebpageMarkdown(
            url = row[webpageMarkdownTable.url],
            markdown = row[webpageMarkdownTable.markdown],
            html = row[webpageMarkdownTable.html],
            httpStatus = row[webpageMarkdownTable.httpStatus],
            httpReason = row[webpageMarkdownTable.httpReason],
            mimeType = row[webpageMarkdownTable.mimeType],
            createdAt = Instant.fromEpochMilliseconds(row[webpageMarkdownTable.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[webpageMarkdownTable.updatedAtEpochMs]),
            version = row[webpageMarkdownTable.version]
        )
    }
}
