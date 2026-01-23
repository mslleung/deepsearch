package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.agents.SnippetClassification
import io.deepsearch.domain.models.entities.WebpageTableInterpretation
import io.deepsearch.domain.repositories.IWebpageTableInterpretationRepository
import io.deepsearch.infrastructure.database.WebpageTableInterpretationCacheTable
import io.deepsearch.infrastructure.services.ITransactionService
import io.deepsearch.infrastructure.services.TransactionService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.batchUpsert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.upsert
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class, ExperimentalEncodingApi::class)
class ExposedWebpageTableInterpretationRepository(
    private val webpageTableInterpretationTable: WebpageTableInterpretationCacheTable,
    private val transactionService: ITransactionService
) : IWebpageTableInterpretationRepository {

    override suspend fun upsert(interpretation: WebpageTableInterpretation): Unit = transactionService.withTransaction {
        val hashBase64 = Base64.encode(interpretation.tableDataHash)

        webpageTableInterpretationTable.upsert(
            keys = arrayOf(webpageTableInterpretationTable.tableDataHash)
        ) {
            it[tableDataHash] = hashBase64
            it[classification] = interpretation.classification.name
            it[markdown] = interpretation.markdown
            it[createdAtEpochMs] = interpretation.createdAt.toEpochMilliseconds()
            it[updatedAtEpochMs] = interpretation.updatedAt.toEpochMilliseconds()
            it[version] = interpretation.version
        }
    }

    override suspend fun batchUpsert(interpretations: List<WebpageTableInterpretation>): Unit = transactionService.withTransaction {
        if (interpretations.isEmpty()) return@withTransaction

        // Sort by hash to ensure consistent lock ordering and prevent deadlocks
        // when multiple concurrent transactions upsert overlapping keys
        val sortedInterpretations = interpretations.sortedBy { Base64.encode(it.tableDataHash) }

        webpageTableInterpretationTable.batchUpsert(
            data = sortedInterpretations,
            keys = arrayOf(webpageTableInterpretationTable.tableDataHash)
        ) { interpretation ->
            val hashBase64 = Base64.encode(interpretation.tableDataHash)
            this[webpageTableInterpretationTable.tableDataHash] = hashBase64
            this[webpageTableInterpretationTable.classification] = interpretation.classification.name
            this[webpageTableInterpretationTable.markdown] = interpretation.markdown
            this[webpageTableInterpretationTable.createdAtEpochMs] = interpretation.createdAt.toEpochMilliseconds()
            this[webpageTableInterpretationTable.updatedAtEpochMs] = interpretation.updatedAt.toEpochMilliseconds()
            this[webpageTableInterpretationTable.version] = interpretation.version
        }
    }

    override suspend fun findByHash(tableDataHash: ByteArray): WebpageTableInterpretation? = transactionService.withTransaction {
        val hashBase64 = Base64.encode(tableDataHash)
        webpageTableInterpretationTable.selectAll()
            .where { webpageTableInterpretationTable.tableDataHash eq hashBase64 }
            .map { mapRowToWebpageTableInterpretation(it) }
            .singleOrNull()
    }

    override suspend fun findByHashes(tableDataHashes: List<ByteArray>): List<WebpageTableInterpretation> = transactionService.withTransaction {
        if (tableDataHashes.isEmpty()) return@withTransaction emptyList()
        
        val hashesBase64 = tableDataHashes.map { Base64.encode(it) }
        webpageTableInterpretationTable.selectAll()
            .where { webpageTableInterpretationTable.tableDataHash inList hashesBase64 }
            .map { mapRowToWebpageTableInterpretation(it) }
            .toList()
    }

    private fun mapRowToWebpageTableInterpretation(row: ResultRow): WebpageTableInterpretation {
        return WebpageTableInterpretation(
            tableDataHash = Base64.decode(row[webpageTableInterpretationTable.tableDataHash]),
            classification = SnippetClassification.fromString(row[webpageTableInterpretationTable.classification]),
            markdown = row[webpageTableInterpretationTable.markdown],
            createdAt = Instant.fromEpochMilliseconds(row[webpageTableInterpretationTable.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[webpageTableInterpretationTable.updatedAtEpochMs]),
            version = row[webpageTableInterpretationTable.version]
        )
    }
}
