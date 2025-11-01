package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.WebpageTableInterpretation
import io.deepsearch.domain.repositories.IWebpageTableInterpretationRepository
import io.deepsearch.infrastructure.database.WebpageTableInterpretationCacheTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.upsert
import kotlin.io.encoding.Base64
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedWebpageTableInterpretationRepository(
    private val webpageTableInterpretationTable: WebpageTableInterpretationCacheTable
) : IWebpageTableInterpretationRepository {

    override suspend fun upsert(interpretation: WebpageTableInterpretation): Unit = suspendTransaction {
        val hashBase64 = Base64.encode(interpretation.tableDataHash)

        webpageTableInterpretationTable.upsert(
            keys = arrayOf(webpageTableInterpretationTable.tableDataHash)
        ) {
            it[tableDataHash] = hashBase64
            it[markdown] = interpretation.markdown
            it[createdAtEpochMs] = interpretation.createdAt.toEpochMilliseconds()
            it[updatedAtEpochMs] = interpretation.updatedAt.toEpochMilliseconds()
            it[version] = interpretation.version
        }
    }

    override suspend fun findByHash(tableDataHash: ByteArray): WebpageTableInterpretation? = suspendTransaction {
        val hashBase64 = Base64.encode(tableDataHash)
        webpageTableInterpretationTable.selectAll()
            .where { webpageTableInterpretationTable.tableDataHash eq hashBase64 }
            .map { mapRowToWebpageTableInterpretation(it) }
            .singleOrNull()
    }

    private fun mapRowToWebpageTableInterpretation(row: ResultRow): WebpageTableInterpretation {
        return WebpageTableInterpretation(
            tableDataHash = Base64.decode(row[webpageTableInterpretationTable.tableDataHash]),
            markdown = row[webpageTableInterpretationTable.markdown],
            createdAt = Instant.fromEpochMilliseconds(row[webpageTableInterpretationTable.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[webpageTableInterpretationTable.updatedAtEpochMs]),
            version = row[webpageTableInterpretationTable.version]
        )
    }
}
