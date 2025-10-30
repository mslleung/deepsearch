package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.WebpagePopup
import io.deepsearch.domain.repositories.IWebpagePopupRepository
import io.deepsearch.infrastructure.database.WebpagePopupTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.upsert
import kotlin.io.encoding.Base64
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedWebpagePopupRepository : IWebpagePopupRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun findByHash(pageHash: ByteArray): WebpagePopup? = suspendTransaction {
        val hashBase64 = Base64.encode(pageHash)
        WebpagePopupTable.selectAll()
            .where { WebpagePopupTable.pageHash eq hashBase64 }
            .map { mapRowToWebpagePopup(it) }
            .singleOrNull()
    }

    override suspend fun upsert(webpagePopup: WebpagePopup): Unit = suspendTransaction {
        val hashBase64 = Base64.encode(webpagePopup.pageHash)

        WebpagePopupTable.upsert(
            keys = arrayOf(WebpagePopupTable.pageHash)
        ) {
            it[pageHash] = hashBase64
            it[popupXPaths] = json.encodeToString(webpagePopup.popupXPaths)
            it[createdAtEpochMs] = webpagePopup.createdAt.toEpochMilliseconds()
            it[updatedAtEpochMs] = webpagePopup.updatedAt.toEpochMilliseconds()
            it[version] = webpagePopup.version
        }
    }

    private fun mapRowToWebpagePopup(row: ResultRow): WebpagePopup {
        return WebpagePopup(
            pageHash = Base64.decode(row[WebpagePopupTable.pageHash]),
            popupXPaths = json.decodeFromString(row[WebpagePopupTable.popupXPaths]),
            createdAt = Instant.fromEpochMilliseconds(row[WebpagePopupTable.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[WebpagePopupTable.updatedAtEpochMs]),
            version = row[WebpagePopupTable.version]
        )
    }
}
