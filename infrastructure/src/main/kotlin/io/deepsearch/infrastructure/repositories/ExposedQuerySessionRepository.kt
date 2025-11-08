package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.exceptions.OptimisticLockException
import io.deepsearch.domain.models.entities.QuerySession
import io.deepsearch.domain.models.entities.FinishReason
import io.deepsearch.domain.models.entities.QuerySessionState
import io.deepsearch.domain.repositories.IQuerySessionRepository
import io.deepsearch.domain.models.valueobjects.SearchBudget
import io.deepsearch.infrastructure.database.QuerySessionTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedQuerySessionRepository(
    private val querySessionTable: QuerySessionTable
) : IQuerySessionRepository {

    override suspend fun save(session: QuerySession): QuerySession = suspendTransaction {
        querySessionTable.insert {
            it[id] = session.id
            it[query] = session.query
            it[url] = session.url
            it[finishReason] = session.finishReason?.name
            it[budgetTimeLimitMs] = session.searchBudget.timeLimitMs
            it[budgetMaxLinks] = session.searchBudget.maxLinks
            it[answer] = session.answer
            it[createdAtEpochMs] = session.createdAt.toEpochMilliseconds()
            it[updatedAtEpochMs] = session.updatedAt.toEpochMilliseconds()
            it[version] = session.version
        }
        
        session
    }

    override suspend fun findById(id: String): QuerySession? = suspendTransaction {
        querySessionTable.selectAll()
            .where { querySessionTable.id eq id }
            .map { mapRowToQuerySession(it) }
            .singleOrNull()
    }

    override suspend fun update(session: QuerySession): QuerySession = suspendTransaction {
        val affectedRows = querySessionTable.update({ 
            (querySessionTable.id eq session.id) and (querySessionTable.version eq session.version) 
        }) {
            it[query] = session.query
            it[url] = session.url
            it[finishReason] = session.finishReason?.name
            it[budgetTimeLimitMs] = session.searchBudget.timeLimitMs
            it[budgetMaxLinks] = session.searchBudget.maxLinks
            it[answer] = session.answer
            it[updatedAtEpochMs] = session.updatedAt.toEpochMilliseconds()
            it[version] = session.version + 1
        }
        
        if (affectedRows == 0) {
            throw OptimisticLockException("QuerySession", session.id, session.version)
        }
        
        session.version += 1
        session
    }

    private fun mapRowToQuerySession(row: ResultRow): QuerySession {
        return QuerySession(
            id = row[querySessionTable.id],
            query = row[querySessionTable.query],
            url = row[querySessionTable.url],
            searchBudget = SearchBudget(
                timeLimitMs = row[querySessionTable.budgetTimeLimitMs],
                maxLinks = row[querySessionTable.budgetMaxLinks]
            ),
            finishReason = row[querySessionTable.finishReason]?.let { FinishReason.valueOf(it) },
            answer = row[querySessionTable.answer],
            createdAt = Instant.fromEpochMilliseconds(row[querySessionTable.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[querySessionTable.updatedAtEpochMs]),
            version = row[querySessionTable.version]
        )
    }
}
