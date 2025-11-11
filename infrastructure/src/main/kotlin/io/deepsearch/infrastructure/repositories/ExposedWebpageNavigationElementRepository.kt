package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.WebpageSemanticElement
import io.deepsearch.domain.models.valueobjects.SemanticElements
import io.deepsearch.domain.repositories.IWebpageNavigationElementRepository
import io.deepsearch.infrastructure.database.WebpageSemanticElementCacheTable
import io.deepsearch.infrastructure.services.ITransactionService
import io.deepsearch.infrastructure.services.TransactionService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.upsert
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedWebpageNavigationElementRepository(
    private val webpageSemanticElementTable: WebpageSemanticElementCacheTable,
    private val transactionService: ITransactionService
) : IWebpageNavigationElementRepository {

    override suspend fun findByHash(pageHash: ByteArray): WebpageSemanticElement? = transactionService.withTransaction {
        val hashBase64 = Base64.encode(pageHash)
        webpageSemanticElementTable.selectAll()
            .where { webpageSemanticElementTable.pageHash eq hashBase64 }
            .map { mapRowToWebpageSemanticElement(it) }
            .singleOrNull()
    }

    override suspend fun upsert(webpageSemanticElement: WebpageSemanticElement): Unit = transactionService.withTransaction {
        val hashBase64 = Base64.encode(webpageSemanticElement.pageHash)

        webpageSemanticElementTable.upsert(
            keys = arrayOf(webpageSemanticElementTable.pageHash)
        ) {
            it[pageHash] = hashBase64
            it[elementsJson] = Json.encodeToString(SemanticElements.serializer(), webpageSemanticElement.elements)
            it[createdAtEpochMs] = webpageSemanticElement.createdAt.toEpochMilliseconds()
            it[updatedAtEpochMs] = webpageSemanticElement.updatedAt.toEpochMilliseconds()
            it[version] = webpageSemanticElement.version
        }
    }

    private fun mapRowToWebpageSemanticElement(row: ResultRow): WebpageSemanticElement {
        return WebpageSemanticElement(
            pageHash = Base64.decode(row[webpageSemanticElementTable.pageHash]),
            elements = row[webpageSemanticElementTable.elementsJson].let {
                Json.decodeFromString(SemanticElements.serializer(), it)
            },
            createdAt = Instant.fromEpochMilliseconds(row[webpageSemanticElementTable.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[webpageSemanticElementTable.updatedAtEpochMs]),
            version = row[webpageSemanticElementTable.version]
        )
    }
}
