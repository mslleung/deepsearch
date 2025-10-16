package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.WebpageMarkdown
import io.deepsearch.domain.repositories.IWebpageMarkdownRepository
import io.deepsearch.infrastructure.database.WebpageMarkdownTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

class ExposedWebpageMarkdownRepository : IWebpageMarkdownRepository {

    override suspend fun findByUrl(url: String): WebpageMarkdown? = suspendTransaction {
        WebpageMarkdownTable.selectAll()
            .where { WebpageMarkdownTable.url eq url }
            .map { mapRowToWebpageMarkdown(it) }
            .singleOrNull()
    }

    override suspend fun upsert(webpage: WebpageMarkdown) = suspendTransaction {
        // Try update first; if nothing updated, insert
        val updated = WebpageMarkdownTable.update({ WebpageMarkdownTable.url eq webpage.url }) {
            it[markdown] = webpage.markdown
            it[html] = webpage.html
            it[updatedAtEpochMs] = webpage.updatedAtEpochMs
        }

        if (updated == 0) {
            WebpageMarkdownTable.insert {
                it[url] = webpage.url
                it[markdown] = webpage.markdown
                it[html] = webpage.html
                it[createdAtEpochMs] = webpage.createdAtEpochMs
                it[updatedAtEpochMs] = webpage.updatedAtEpochMs
            }
        }
    }

    private fun mapRowToWebpageMarkdown(row: ResultRow): WebpageMarkdown {
        return WebpageMarkdown(
            url = row[WebpageMarkdownTable.url],
            markdown = row[WebpageMarkdownTable.markdown],
            html = row[WebpageMarkdownTable.html],
            createdAtEpochMs = row[WebpageMarkdownTable.createdAtEpochMs],
            updatedAtEpochMs = row[WebpageMarkdownTable.updatedAtEpochMs]
        )
    }
}

