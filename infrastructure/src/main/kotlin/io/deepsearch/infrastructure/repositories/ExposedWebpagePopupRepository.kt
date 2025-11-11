package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.WebpagePopup
import io.deepsearch.domain.repositories.IWebpagePopupRepository
import io.deepsearch.infrastructure.database.WebpagePopupCacheTable
import io.deepsearch.infrastructure.services.ITransactionService
import io.deepsearch.infrastructure.services.TransactionService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.upsert
import kotlin.io.encoding.Base64
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedWebpagePopupRepository(
    private val webpagePopupTable: WebpagePopupCacheTable,
    private val transactionService: ITransactionService
) : IWebpagePopupRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun findByHash(pageHash: ByteArray): WebpagePopup? = transactionService.withTransaction {
        val hashBase64 = Base64.encode(pageHash)
        webpagePopupTable.selectAll()
            .where { webpagePopupTable.pageHash eq hashBase64 }
            .map { mapRowToWebpagePopup(it) }
            .singleOrNull()
    }

    override suspend fun upsert(webpagePopup: WebpagePopup): Unit = transactionService.withTransaction {
        val hashBase64 = Base64.encode(webpagePopup.pageHash)

        webpagePopupTable.upsert(
            keys = arrayOf(webpagePopupTable.pageHash)
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
            pageHash = Base64.decode(row[webpagePopupTable.pageHash]),
            popupXPaths = json.decodeFromString(row[webpagePopupTable.popupXPaths]),
            createdAt = Instant.fromEpochMilliseconds(row[webpagePopupTable.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[webpagePopupTable.updatedAtEpochMs]),
            version = row[webpagePopupTable.version]
        )
    }
}
