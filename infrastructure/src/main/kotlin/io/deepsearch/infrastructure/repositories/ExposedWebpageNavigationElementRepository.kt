package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.WebpageSemanticElement
import io.deepsearch.domain.models.valueobjects.SemanticElements
import io.deepsearch.domain.repositories.IWebpageNavigationElementRepository
import io.deepsearch.infrastructure.database.WebpagePopupTable
import io.deepsearch.infrastructure.database.WebpageSemanticElementTable
import io.deepsearch.infrastructure.database.WebpageTableInterpretationTable.createdAtEpochMs
import io.deepsearch.infrastructure.database.WebpageTableInterpretationTable.updatedAtEpochMs
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.upsert
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedWebpageNavigationElementRepository : IWebpageNavigationElementRepository {

    override suspend fun findByHash(pageHash: ByteArray): WebpageSemanticElement? = suspendTransaction {
        val hashBase64 = Base64.encode(pageHash)
        WebpageSemanticElementTable.selectAll()
            .where { WebpageSemanticElementTable.pageHash eq hashBase64 }
            .map { mapRowToWebpageSemanticElement(it) }
            .singleOrNull()
    }

    override suspend fun upsert(webpageSemanticElement: WebpageSemanticElement): Unit = suspendTransaction {
        val hashBase64 = Base64.encode(webpageSemanticElement.pageHash)

        WebpageSemanticElementTable.upsert(
            keys = arrayOf(WebpageSemanticElementTable.pageHash)
        ) {
            it[pageHash] = hashBase64
            it[elementsJson] = Json.encodeToString(SemanticElements.serializer(), webpageSemanticElement.elements)
            it[createdAtEpochMs] = webpageSemanticElement.createdAt.toEpochMilliseconds()
            it[updatedAtEpochMs] = webpageSemanticElement.updatedAt.toEpochMilliseconds()
        }
    }

    private fun mapRowToWebpageSemanticElement(row: ResultRow): WebpageSemanticElement {
        return WebpageSemanticElement(
            pageHash = Base64.decode(row[WebpageSemanticElementTable.pageHash]),
            elements = row[WebpageSemanticElementTable.elementsJson].let {
                Json.decodeFromString(SemanticElements.serializer(), it)
            },
            createdAt = Instant.fromEpochMilliseconds(row[WebpageSemanticElementTable.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[WebpageSemanticElementTable.updatedAtEpochMs]),
        )
    }
}

