package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.WebpagePopup
import io.deepsearch.domain.repositories.IWebpagePopupRepository
import io.deepsearch.infrastructure.database.WebpagePopupTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

class ExposedWebpagePopupRepository : IWebpagePopupRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun findByHash(pageHash: ByteArray): WebpagePopup? = suspendTransaction {
        WebpagePopupTable.selectAll()
            .where { WebpagePopupTable.pageHash eq pageHash }
            .map { mapRowToWebpagePopup(it) }
            .singleOrNull()
    }

    override suspend fun upsert(webpagePopup: WebpagePopup): Unit = suspendTransaction {
        val existing = WebpagePopupTable.selectAll()
            .where { WebpagePopupTable.pageHash eq webpagePopup.pageHash }
            .map { it }
            .singleOrNull()

        if (existing != null) {
            WebpagePopupTable.update({ WebpagePopupTable.pageHash eq webpagePopup.pageHash }) {
                it[popupXPaths] = json.encodeToString(webpagePopup.popupXPaths)
            }
        } else {
            WebpagePopupTable.insert {
                it[pageHash] = webpagePopup.pageHash
                it[popupXPaths] = json.encodeToString(webpagePopup.popupXPaths)
            }
        }
        Unit
    }

    private fun mapRowToWebpagePopup(row: ResultRow): WebpagePopup {
        return WebpagePopup(
            pageHash = row[WebpagePopupTable.pageHash],
            popupXPaths = json.decodeFromString(row[WebpagePopupTable.popupXPaths])
        )
    }
}
