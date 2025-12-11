package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.valueobjects.ProxyRuleId
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.proxy.IProxyRuleRepository
import io.deepsearch.domain.proxy.ProxyRule
import io.deepsearch.domain.proxy.ProxyType
import io.deepsearch.infrastructure.database.ProxyRuleTable
import io.deepsearch.infrastructure.services.ITransactionService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedProxyRuleRepository(
    private val proxyRuleTable: ProxyRuleTable,
    private val transactionService: ITransactionService
) : IProxyRuleRepository {

    override suspend fun save(proxyRule: ProxyRule): ProxyRule = transactionService.withTransaction {
        val id = proxyRuleTable.insert {
            it[userId] = proxyRule.userId.value
            it[urlPattern] = proxyRule.urlPattern
            it[proxyType] = proxyRule.proxyType.name
            it[customProxyUrl] = proxyRule.customProxyUrl
            it[createdAtEpochMs] = proxyRule.createdAt.toEpochMilliseconds()
            it[updatedAtEpochMs] = proxyRule.updatedAt.toEpochMilliseconds()
        }[proxyRuleTable.id]

        proxyRule.id = ProxyRuleId(id)
        proxyRule
    }

    override suspend fun findById(id: ProxyRuleId): ProxyRule? = transactionService.withTransaction {
        proxyRuleTable.selectAll()
            .where { proxyRuleTable.id eq id.value }
            .map { mapRowToProxyRule(it) }
            .singleOrNull()
    }

    override suspend fun findByUserId(userId: UserId): List<ProxyRule> = transactionService.withTransaction {
        proxyRuleTable.selectAll()
            .where { proxyRuleTable.userId eq userId.value }
            .map { mapRowToProxyRule(it) }
            .toList()
    }

    override suspend fun findByUserIdAndUrlPattern(userId: UserId, urlPattern: String): ProxyRule? = 
        transactionService.withTransaction {
            proxyRuleTable.selectAll()
                .where { (proxyRuleTable.userId eq userId.value) and (proxyRuleTable.urlPattern eq urlPattern) }
                .map { mapRowToProxyRule(it) }
                .singleOrNull()
        }

    override suspend fun update(proxyRule: ProxyRule): ProxyRule = transactionService.withTransaction {
        proxyRuleTable.update({ proxyRuleTable.id eq proxyRule.id!!.value }) {
            it[urlPattern] = proxyRule.urlPattern
            it[proxyType] = proxyRule.proxyType.name
            it[customProxyUrl] = proxyRule.customProxyUrl
            it[updatedAtEpochMs] = proxyRule.updatedAt.toEpochMilliseconds()
        }
        proxyRule
    }

    override suspend fun delete(id: ProxyRuleId): Boolean = transactionService.withTransaction {
        proxyRuleTable.deleteWhere { proxyRuleTable.id eq id.value } > 0
    }

    override suspend fun isOwnedBy(id: ProxyRuleId, userId: UserId): Boolean = transactionService.withTransaction {
        proxyRuleTable.selectAll()
            .where { (proxyRuleTable.id eq id.value) and (proxyRuleTable.userId eq userId.value) }
            .limit(1)
            .count() > 0
    }

    override suspend fun countByUserId(userId: UserId): Long = transactionService.withTransaction {
        proxyRuleTable.selectAll()
            .where { proxyRuleTable.userId eq userId.value }
            .count()
    }

    private fun mapRowToProxyRule(row: ResultRow): ProxyRule {
        return ProxyRule(
            id = ProxyRuleId(row[proxyRuleTable.id]),
            userId = UserId(row[proxyRuleTable.userId]),
            urlPattern = row[proxyRuleTable.urlPattern],
            proxyType = ProxyType.valueOf(row[proxyRuleTable.proxyType]),
            customProxyUrl = row[proxyRuleTable.customProxyUrl],
            createdAt = Instant.fromEpochMilliseconds(row[proxyRuleTable.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[proxyRuleTable.updatedAtEpochMs])
        )
    }
}

