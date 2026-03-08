package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.exceptions.OptimisticLockException
import io.deepsearch.domain.models.entities.QuerySession
import io.deepsearch.domain.models.entities.FinishReason
import io.deepsearch.domain.repositories.IQuerySessionRepository
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.SearchBudget
import io.deepsearch.domain.models.valueobjects.SearchMode
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.infrastructure.database.ApiKeyTable
import io.deepsearch.infrastructure.database.QuerySessionTable
import io.deepsearch.infrastructure.services.ITransactionService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import java.net.URI
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedQuerySessionRepository(
    private val querySessionTable: QuerySessionTable,
    private val apiKeyTable: ApiKeyTable,
    private val transactionService: ITransactionService
) : IQuerySessionRepository {

    override suspend fun save(session: QuerySession): QuerySession = transactionService.withTransaction {
        querySessionTable.insert {
            it[id] = session.id.value
            it[query] = session.query
            it[url] = session.url
            it[apiKeyId] = session.apiKeyId.value
            it[searchMode] = session.searchMode.name
            it[finishReason] = session.finishReason?.name
            it[budgetTimeLimitMs] = session.searchBudget.timeLimitMs
            it[budgetMaxLinks] = session.searchBudget.maxLinks
            it[budgetMaxSerpCalls] = session.searchBudget.maxSerpCalls
            it[answer] = session.answer
            it[answerFound] = session.answerFound
            it[imageIds] = Json.encodeToString(session.imageIds)
            it[durationMs] = session.durationMs
            it[createdAtEpochMs] = session.createdAt.toEpochMilliseconds()
            it[updatedAtEpochMs] = session.updatedAt.toEpochMilliseconds()
            it[version] = session.version
            it[previousSessionId] = session.previousSessionId?.value
            it[rootSessionId] = session.rootSessionId?.value
        }
        
        session
    }

    override suspend fun findById(id: QuerySessionId): QuerySession? = transactionService.withTransaction {
        querySessionTable.selectAll()
            .where { querySessionTable.id eq id.value }
            .map { mapRowToQuerySession(it) }
            .singleOrNull()
    }

    override suspend fun update(session: QuerySession): QuerySession = transactionService.withTransaction {
        val affectedRows = querySessionTable.update({ 
            (querySessionTable.id eq session.id.value) and (querySessionTable.version eq session.version) 
        }) {
            it[query] = session.query
            it[url] = session.url
            it[apiKeyId] = session.apiKeyId.value
            it[searchMode] = session.searchMode.name
            it[finishReason] = session.finishReason?.name
            it[budgetTimeLimitMs] = session.searchBudget.timeLimitMs
            it[budgetMaxLinks] = session.searchBudget.maxLinks
            it[budgetMaxSerpCalls] = session.searchBudget.maxSerpCalls
            it[answer] = session.answer
            it[answerFound] = session.answerFound
            it[imageIds] = Json.encodeToString(session.imageIds)
            it[durationMs] = session.durationMs
            it[updatedAtEpochMs] = session.updatedAt.toEpochMilliseconds()
            it[version] = session.version + 1
            it[previousSessionId] = session.previousSessionId?.value
            it[rootSessionId] = session.rootSessionId?.value
        }
        
        if (affectedRows == 0) {
            throw OptimisticLockException("QuerySession", session.id.value, session.version)
        }
        
        session.version += 1
        session
    }

    override suspend fun countSessionsSince(apiKeyId: ApiKeyId, since: Instant): Long = transactionService.withTransaction {
        val results = querySessionTable.selectAll()
            .where { 
                (querySessionTable.apiKeyId eq apiKeyId.value) and
                (querySessionTable.createdAtEpochMs greaterEq since.toEpochMilliseconds())
            }
            .map { it }
            .toList()
        results.size.toLong()
    }

    override suspend fun findByApiKeyIdAndDateRange(apiKeyId: ApiKeyId, start: Instant, end: Instant): List<QuerySession> = transactionService.withTransaction {
        querySessionTable.selectAll()
            .where {
                (querySessionTable.apiKeyId eq apiKeyId.value) and
                (querySessionTable.createdAtEpochMs greaterEq start.toEpochMilliseconds()) and
                (querySessionTable.createdAtEpochMs less end.toEpochMilliseconds())
            }
            .map { mapRowToQuerySession(it) }
            .toList()
    }

    override suspend fun findByUserIdAndDateRange(userId: UserId, start: Instant, end: Instant): List<QuerySession> = transactionService.withTransaction {
        querySessionTable
            .join(apiKeyTable, JoinType.INNER, querySessionTable.apiKeyId, apiKeyTable.id)
            .selectAll()
            .where {
                (apiKeyTable.userId eq userId.value) and
                (querySessionTable.createdAtEpochMs greaterEq start.toEpochMilliseconds()) and
                (querySessionTable.createdAtEpochMs less end.toEpochMilliseconds())
            }
            .map { mapRowToQuerySession(it) }
            .toList()
    }

    override suspend fun findByUserIdPaginated(userId: UserId, offset: Int, limit: Int): List<QuerySession> = transactionService.withTransaction {
        querySessionTable
            .join(apiKeyTable, JoinType.INNER, querySessionTable.apiKeyId, apiKeyTable.id)
            .selectAll()
            .where { apiKeyTable.userId eq userId.value }
            .orderBy(querySessionTable.createdAtEpochMs to org.jetbrains.exposed.v1.core.SortOrder.DESC)
            .limit(limit)
            .offset(offset.toLong())
            .map { mapRowToQuerySession(it) }
            .toList()
    }

    override suspend fun countByUserId(userId: UserId): Long = transactionService.withTransaction {
        val results = querySessionTable
            .join(apiKeyTable, JoinType.INNER, querySessionTable.apiKeyId, apiKeyTable.id)
            .selectAll()
            .where { apiKeyTable.userId eq userId.value }
            .map { it }
            .toList()
        results.size.toLong()
    }

    override suspend fun findByUserIdWithFilters(
        userId: UserId,
        search: String?,
        domain: String?,
        status: String?,
        sortBy: String,
        sortOrder: String,
        offset: Int,
        limit: Int
    ): List<QuerySession> = transactionService.withTransaction {
        val baseQuery = querySessionTable
            .join(apiKeyTable, JoinType.INNER, querySessionTable.apiKeyId, apiKeyTable.id)
            .selectAll()
            .where { 
                buildFilterCondition(userId, search, domain, status)
            }

        // Apply sorting
        val sortColumn = when (sortBy) {
            "duration" -> querySessionTable.durationMs
            "domain" -> querySessionTable.url
            else -> querySessionTable.createdAtEpochMs
        }
        val order = if (sortOrder.equals("asc", ignoreCase = true)) SortOrder.ASC else SortOrder.DESC

        baseQuery
            .orderBy(sortColumn to order)
            .limit(limit)
            .offset(offset.toLong())
            .map { mapRowToQuerySession(it) }
            .toList()
    }

    override suspend fun countByUserIdWithFilters(
        userId: UserId,
        search: String?,
        domain: String?,
        status: String?
    ): Long = transactionService.withTransaction {
        val results = querySessionTable
            .join(apiKeyTable, JoinType.INNER, querySessionTable.apiKeyId, apiKeyTable.id)
            .selectAll()
            .where { 
                buildFilterCondition(userId, search, domain, status)
            }
            .map { it }
            .toList()
        results.size.toLong()
    }

    override suspend fun getDistinctDomainsByUserId(userId: UserId): List<String> = transactionService.withTransaction {
        querySessionTable
            .join(apiKeyTable, JoinType.INNER, querySessionTable.apiKeyId, apiKeyTable.id)
            .selectAll()
            .where { apiKeyTable.userId eq userId.value }
            .map { row -> extractDomain(row[querySessionTable.url]) }
            .toList()
            .distinct()
            .sorted()
    }

    override suspend fun findAllByUserId(userId: UserId): List<QuerySession> = transactionService.withTransaction {
        querySessionTable
            .join(apiKeyTable, JoinType.INNER, querySessionTable.apiKeyId, apiKeyTable.id)
            .selectAll()
            .where { apiKeyTable.userId eq userId.value }
            .map { mapRowToQuerySession(it) }
            .toList()
    }

    private fun buildFilterCondition(
        userId: UserId,
        search: String?,
        domain: String?,
        status: String?
    ): Op<Boolean> {
        var condition: Op<Boolean> = apiKeyTable.userId eq userId.value

        // Search filter (query, URL, or status)
        if (!search.isNullOrBlank()) {
            val searchPattern = "%${search.lowercase()}%"
            condition = condition and (
                (querySessionTable.query.lowerCase() like searchPattern) or
                (querySessionTable.url.lowerCase() like searchPattern) or
                (querySessionTable.finishReason.lowerCase() like searchPattern)
            )
        }

        // Domain filter
        if (!domain.isNullOrBlank()) {
            // Match URLs containing the domain
            condition = condition and (querySessionTable.url like "%$domain%")
        }

        // Status filter
        if (!status.isNullOrBlank()) {
            condition = condition and (querySessionTable.finishReason eq status)
        }

        return condition
    }

    private fun extractDomain(url: String): String {
        return try {
            val uri = URI(url)
            uri.host?.lowercase() ?: url
        } catch (e: Exception) {
            url
        }
    }

    private fun mapRowToQuerySession(row: ResultRow): QuerySession {
        val imageIdsJson = row[querySessionTable.imageIds]
        val imageIds: List<String> = try {
            Json.decodeFromString(imageIdsJson)
        } catch (e: Exception) {
            emptyList()
        }
        
        return QuerySession(
            id = QuerySessionId(row[querySessionTable.id]),
            query = row[querySessionTable.query],
            url = row[querySessionTable.url],
            apiKeyId = ApiKeyId(row[querySessionTable.apiKeyId]),
            searchMode = SearchMode.valueOf(row[querySessionTable.searchMode]),
            searchBudget = SearchBudget(
                timeLimitMs = row[querySessionTable.budgetTimeLimitMs],
                maxLinks = row[querySessionTable.budgetMaxLinks],
                maxSerpCalls = row[querySessionTable.budgetMaxSerpCalls]
            ),
            finishReason = row[querySessionTable.finishReason]?.let { FinishReason.valueOf(it) },
            answer = row[querySessionTable.answer],
            answerFound = row[querySessionTable.answerFound],
            imageIds = imageIds,
            durationMs = row[querySessionTable.durationMs],
            createdAt = Instant.fromEpochMilliseconds(row[querySessionTable.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[querySessionTable.updatedAtEpochMs]),
            version = row[querySessionTable.version],
            previousSessionId = row[querySessionTable.previousSessionId]?.let { QuerySessionId(it) },
            rootSessionId = row[querySessionTable.rootSessionId]?.let { QuerySessionId(it) }
        )
    }
    
    /**
     * Find all sessions in a continuation chain by root session ID.
     * Returns sessions ordered by creation time (oldest first).
     * Includes the root session itself.
     */
    override suspend fun findSessionChain(rootSessionId: QuerySessionId): List<QuerySession> = transactionService.withTransaction {
        querySessionTable.selectAll()
            .where { 
                (querySessionTable.id eq rootSessionId.value) or 
                (querySessionTable.rootSessionId eq rootSessionId.value) 
            }
            .orderBy(querySessionTable.createdAtEpochMs to SortOrder.ASC)
            .map { mapRowToQuerySession(it) }
            .toList()
    }
}
