package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.QuerySession
import io.deepsearch.domain.models.entities.FinishReason
import io.deepsearch.domain.models.entities.QuerySessionState
import io.deepsearch.domain.repositories.IQuerySessionRepository
import io.deepsearch.domain.models.valueobjects.SearchBudget
import io.deepsearch.infrastructure.database.QuerySessionTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

class ExposedQuerySessionRepository : IQuerySessionRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun save(session: QuerySession): QuerySession = suspendTransaction {
        QuerySessionTable.insert {
            it[id] = session.id
            it[query] = session.query
            it[url] = session.url
            it[state] = session.state.name
            it[finishReason] = session.finishReason?.name
            it[budgetTimeLimitMs] = session.searchBudget.timeLimitMs
            it[budgetMaxLinks] = session.searchBudget.maxLinks
            it[answerComplete] = session.answerComplete
            it[answer] = session.answer
            it[traversedUrls] = json.encodeToString(session.traversedUrls.toList())
            it[sourcesDiscovered] = json.encodeToString(session.sourcesDiscovered)
            it[createdAtEpochMs] = session.createdAtEpochMs
            it[updatedAtEpochMs] = session.updatedAtEpochMs
        }
        session
    }

    override suspend fun findById(id: String): QuerySession? = suspendTransaction {
        QuerySessionTable.selectAll()
            .where { QuerySessionTable.id eq id }
            .map { mapRowToQuerySession(it) }
            .singleOrNull()
    }

    override suspend fun update(session: QuerySession): QuerySession = suspendTransaction {
        QuerySessionTable.update({ QuerySessionTable.id eq session.id }) {
            it[query] = session.query
            it[url] = session.url
            it[state] = session.state.name
            it[finishReason] = session.finishReason?.name
            it[budgetTimeLimitMs] = session.searchBudget.timeLimitMs
            it[budgetMaxLinks] = session.searchBudget.maxLinks
            it[answerComplete] = session.answerComplete
            it[answer] = session.answer
            it[traversedUrls] = json.encodeToString(session.traversedUrls.toList())
            it[sourcesDiscovered] = json.encodeToString(session.sourcesDiscovered)
            it[updatedAtEpochMs] = session.updatedAtEpochMs
        }
        session
    }

    private fun mapRowToQuerySession(row: ResultRow): QuerySession {
        return QuerySession(
            id = row[QuerySessionTable.id],
            query = row[QuerySessionTable.query],
            url = row[QuerySessionTable.url],
            state = QuerySessionState.valueOf(row[QuerySessionTable.state]),
            searchBudget = SearchBudget(
                timeLimitMs = row[QuerySessionTable.budgetTimeLimitMs],
                maxLinks = row[QuerySessionTable.budgetMaxLinks]
            ),
            finishReason = row[QuerySessionTable.finishReason]?.let { FinishReason.valueOf(it) },
            answerComplete = row[QuerySessionTable.answerComplete],
            answer = row[QuerySessionTable.answer],
            traversedUrls = json.decodeFromString<List<String>>(row[QuerySessionTable.traversedUrls]).toMutableSet(),
            sourcesDiscovered = json.decodeFromString<List<String>>(row[QuerySessionTable.sourcesDiscovered])
                .toMutableList(),
            createdAtEpochMs = row[QuerySessionTable.createdAtEpochMs],
            updatedAtEpochMs = row[QuerySessionTable.updatedAtEpochMs],
        )
    }
}

