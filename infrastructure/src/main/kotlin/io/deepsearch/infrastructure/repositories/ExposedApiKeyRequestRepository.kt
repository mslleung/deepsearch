package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.ApiKeyRequest
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.repositories.IApiKeyRequestRepository
import io.deepsearch.infrastructure.database.ApiKeyRequestTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedApiKeyRequestRepository : IApiKeyRequestRepository {

    override suspend fun save(request: ApiKeyRequest): ApiKeyRequest = suspendTransaction {
        val id = ApiKeyRequestTable.insert {
            it[apiKeyId] = request.apiKeyId.value
            it[requestedAtEpochMs] = request.requestedAt.toEpochMilliseconds()
        }[ApiKeyRequestTable.id]

        request.id = id
        request
    }

    override suspend fun countRequestsSince(apiKeyId: ApiKeyId, since: Instant): Long = suspendTransaction {
        val results = ApiKeyRequestTable.selectAll()
            .where { 
                (ApiKeyRequestTable.apiKeyId eq apiKeyId.value) and
                (ApiKeyRequestTable.requestedAtEpochMs greaterEq since.toEpochMilliseconds())
            }
            .map { it }
            .toList()
        results.size.toLong()
    }

    override suspend fun deleteRequestsBefore(before: Instant): Int = suspendTransaction {
        ApiKeyRequestTable.deleteWhere { 
            ApiKeyRequestTable.requestedAtEpochMs less before.toEpochMilliseconds() 
        }
    }
}

