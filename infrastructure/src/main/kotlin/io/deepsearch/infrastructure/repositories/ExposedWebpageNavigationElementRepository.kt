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
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class ExposedWebpageNavigationElementRepository : IWebpageNavigationElementRepository {

    override suspend fun findByHash(pageHash: ByteArray): WebpageNavigationElement? = suspendTransaction {
        val hashBase64 = Base64.encode(pageHash)
        WebpageNavigationElementTable.selectAll()
            .where { WebpageNavigationElementTable.pageHash eq hashBase64 }
            .map { mapRowToWebpageNavigationElement(it) }
            .singleOrNull()
    }

    override suspend fun upsert(webpageNavigationElement: WebpageNavigationElement): Unit = suspendTransaction {
        val hashBase64 = Base64.encode(webpageNavigationElement.pageHash)
        
        // Try update first; if nothing updated, insert
        val updated = WebpageNavigationElementTable.update({ WebpageNavigationElementTable.pageHash eq hashBase64 }) {
            it[elementsJson] = Json.encodeToString(webpageNavigationElement.elements)
        }

        if (updated == 0) {
            WebpageNavigationElementTable.insert {
                it[pageHash] = hashBase64
                it[elementsJson] = Json.encodeToString(webpageNavigationElement.elements)
            }
        }
        Unit
    }

    private fun mapRowToWebpageNavigationElement(row: ResultRow): WebpageNavigationElement {
        return WebpageNavigationElement(
            pageHash = Base64.decode(row[WebpageNavigationElementTable.pageHash]),
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

