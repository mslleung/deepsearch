package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.QueryAnswer
import io.deepsearch.domain.repositories.IQueryAnswerRepository
import io.deepsearch.infrastructure.database.QueryAnswerTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class ExposedQueryAnswerRepository : IQueryAnswerRepository {

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun upsert(queryAnswer: QueryAnswer) = suspendTransaction {
        val hashBase64 = Base64.encode(queryAnswer.queryHash)

        // Try update first; if nothing updated, insert
        val updated = QueryAnswerTable.update({ QueryAnswerTable.queryHash eq hashBase64 }) {
            it[answer] = queryAnswer.answer
            it[updatedAtEpochMs] = queryAnswer.updatedAtEpochMs
        }

        if (updated == 0) {
            QueryAnswerTable.insert {
                it[queryHash] = hashBase64
                it[answer] = queryAnswer.answer
                it[createdAtEpochMs] = queryAnswer.createdAtEpochMs
                it[updatedAtEpochMs] = queryAnswer.updatedAtEpochMs
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun findByHash(queryHash: ByteArray): QueryAnswer? = suspendTransaction {
        val hashBase64 = Base64.encode(queryHash)
        QueryAnswerTable.selectAll()
            .where { QueryAnswerTable.queryHash eq hashBase64 }
            .map { mapRowToQueryAnswer(it) }
            .singleOrNull()
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun mapRowToQueryAnswer(row: ResultRow): QueryAnswer {
        return QueryAnswer(
            queryHash = Base64.decode(row[QueryAnswerTable.queryHash]),
            answer = row[QueryAnswerTable.answer],
            createdAtEpochMs = row[QueryAnswerTable.createdAtEpochMs],
            updatedAtEpochMs = row[QueryAnswerTable.updatedAtEpochMs],
        )
    }
}
