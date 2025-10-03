package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.WebpageTableInterpretation
import io.deepsearch.domain.repositories.IWebpageTableInterpretationRepository
import io.deepsearch.infrastructure.database.WebpageTableInterpretationTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.io.encoding.Base64

class ExposedWebpageTableInterpretationRepository : IWebpageTableInterpretationRepository {

    override suspend fun upsert(interpretation: WebpageTableInterpretation) = suspendTransaction {
        val hashBase64 = Base64.encode(interpretation.tableDataHash)

        // Try update first; if nothing updated, insert
        val updated = WebpageTableInterpretationTable.update({ WebpageTableInterpretationTable.tableDataHash eq hashBase64 }) {
            it[markdown] = interpretation.markdown
            it[updatedAtEpochMs] = interpretation.updatedAtEpochMs
        }

        if (updated == 0) {
            WebpageTableInterpretationTable.insert {
                it[tableDataHash] = hashBase64
                it[markdown] = interpretation.markdown
                it[createdAtEpochMs] = interpretation.createdAtEpochMs
                it[updatedAtEpochMs] = interpretation.updatedAtEpochMs
            }
        }
    }

    override suspend fun findByHash(tableDataHash: ByteArray): WebpageTableInterpretation? = suspendTransaction {
        val hashBase64 = Base64.encode(tableDataHash)
        WebpageTableInterpretationTable.selectAll()
            .where { WebpageTableInterpretationTable.tableDataHash eq hashBase64 }
            .map { mapRowToWebpageTableInterpretation(it) }
            .singleOrNull()
    }

    private fun mapRowToWebpageTableInterpretation(row: ResultRow): WebpageTableInterpretation {
        return WebpageTableInterpretation(
            tableDataHash = Base64.decode(row[WebpageTableInterpretationTable.tableDataHash]),
            markdown = row[WebpageTableInterpretationTable.markdown],
            createdAtEpochMs = row[WebpageTableInterpretationTable.createdAtEpochMs],
            updatedAtEpochMs = row[WebpageTableInterpretationTable.updatedAtEpochMs],
        )
    }
}

