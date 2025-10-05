package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.WebpageNavigationElement
import io.deepsearch.domain.models.valueobjects.NavigationElementMatch
import io.deepsearch.domain.repositories.IWebpageNavigationElementRepository
import io.deepsearch.infrastructure.database.WebpageNavigationElementTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ExposedWebpageNavigationElementRepository : IWebpageNavigationElementRepository {

    override suspend fun findByHash(pageHash: ByteArray): WebpageNavigationElement? = suspendTransaction {
        WebpageNavigationElementTable.selectAll()
            .where { WebpageNavigationElementTable.pageHash eq pageHash }
            .map { mapRowToWebpageNavigationElement(it) }
            .singleOrNull()
    }

    override suspend fun upsert(webpageNavigationElement: WebpageNavigationElement): Unit = suspendTransaction {
        val existing = WebpageNavigationElementTable.selectAll()
            .where { WebpageNavigationElementTable.pageHash eq webpageNavigationElement.pageHash }
            .map { it }
            .singleOrNull()

        if (existing != null) {
            WebpageNavigationElementTable.update({ WebpageNavigationElementTable.pageHash eq webpageNavigationElement.pageHash }) {
                it[elementsJson] = if (webpageNavigationElement.elements.isNotEmpty()) Json.encodeToString(webpageNavigationElement.elements) else null
            }
        } else {
            WebpageNavigationElementTable.insert {
                it[pageHash] = webpageNavigationElement.pageHash
                it[elementsJson] = if (webpageNavigationElement.elements.isNotEmpty()) Json.encodeToString(webpageNavigationElement.elements) else null
            }
        }
        Unit
    }

    private fun mapRowToWebpageNavigationElement(row: ResultRow): WebpageNavigationElement {
        return WebpageNavigationElement(
            pageHash = row[WebpageNavigationElementTable.pageHash],
            elements = row[WebpageNavigationElementTable.elementsJson]?.let {
                try {
                    Json.decodeFromString<List<NavigationElementMatch>>(it)
                } catch (e: Exception) {
                    emptyList()
                }
            } ?: emptyList()
        )
    }
}

