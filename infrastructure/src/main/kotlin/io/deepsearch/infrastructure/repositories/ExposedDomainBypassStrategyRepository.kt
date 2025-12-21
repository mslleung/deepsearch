package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.DomainBypassStrategy
import io.deepsearch.domain.models.valueobjects.BypassStrategy
import io.deepsearch.domain.repositories.BypassStrategyStatistics
import io.deepsearch.domain.repositories.IDomainBypassStrategyRepository
import io.deepsearch.infrastructure.database.DomainBypassStrategyTable
import io.deepsearch.infrastructure.services.IDatabaseConfigurationService
import io.deepsearch.infrastructure.services.ITransactionService
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Exposed implementation of IDomainBypassStrategyRepository.
 */
@OptIn(ExperimentalTime::class)
class ExposedDomainBypassStrategyRepository(
    private val table: DomainBypassStrategyTable,
    private val transactionService: ITransactionService
) : IDomainBypassStrategyRepository {

    override suspend fun findByDomain(domain: String): DomainBypassStrategy? {
        return transactionService.withTransaction {
            table.selectAll()
                .where { table.domain eq domain.lowercase() }
                .firstOrNull()
                ?.let { row -> rowToEntity(row) }
        }
    }

    override suspend fun getOrCreate(domain: String): DomainBypassStrategy {
        val existing = findByDomain(domain)
        if (existing != null) return existing

        return save(DomainBypassStrategy.newForDomain(domain.lowercase()))
    }

    override suspend fun save(strategy: DomainBypassStrategy): DomainBypassStrategy {
        val now = Clock.System.now()
        val normalizedDomain = strategy.domain.lowercase()

        return transactionService.withTransaction {
            val existingId = strategy.id
            if (existingId != null) {
                // Update existing
                table.update({ table.id eq existingId }) {
                    it[domain] = normalizedDomain
                    it[this.strategy] = strategy.strategy.name
                    it[lastBlockedAtEpochMs] = strategy.lastBlockedAt?.toEpochMilliseconds()
                    it[lastSuccessAtEpochMs] = strategy.lastSuccessAt?.toEpochMilliseconds()
                    it[consecutiveFailures] = strategy.consecutiveFailures
                    it[updatedAtEpochMs] = now.toEpochMilliseconds()
                }
                strategy.copy(updatedAt = now)
            } else {
                // Check if domain already exists (race condition)
                val existing = table.selectAll()
                    .where { table.domain eq normalizedDomain }
                    .firstOrNull()

                if (existing != null) {
                    // Return existing record
                    rowToEntity(existing)
                } else {
                    // Insert new
                    val id = table.insert {
                        it[domain] = normalizedDomain
                        it[this.strategy] = strategy.strategy.name
                        it[lastBlockedAtEpochMs] = strategy.lastBlockedAt?.toEpochMilliseconds()
                        it[lastSuccessAtEpochMs] = strategy.lastSuccessAt?.toEpochMilliseconds()
                        it[consecutiveFailures] = strategy.consecutiveFailures
                        it[createdAtEpochMs] = now.toEpochMilliseconds()
                        it[updatedAtEpochMs] = now.toEpochMilliseconds()
                    } get table.id

                    strategy.copy(
                        id = id,
                        domain = normalizedDomain,
                        createdAt = now,
                        updatedAt = now
                    )
                }
            }
        }
    }

    override suspend fun updateStrategy(domain: String, newStrategy: BypassStrategy): DomainBypassStrategy {
        val existing = getOrCreate(domain)
        val updated = existing.copy(
            strategy = newStrategy,
            updatedAt = Clock.System.now()
        )
        return save(updated)
    }

    override suspend fun recordSuccess(domain: String): DomainBypassStrategy {
        val existing = getOrCreate(domain)
        val updated = existing.recordSuccess()
        return save(updated)
    }

    override suspend fun recordBlocked(domain: String): DomainBypassStrategy {
        val existing = getOrCreate(domain)
        val updated = existing.recordBlocked()
        return save(updated)
    }

    override suspend fun findByStrategy(strategy: BypassStrategy): List<DomainBypassStrategy> {
        return transactionService.withTransaction {
            table.selectAll()
                .where { table.strategy eq strategy.name }
                .toList()
                .map { row -> rowToEntity(row) }
        }
    }

    override suspend fun getStatistics(): BypassStrategyStatistics {
        return transactionService.withTransaction {
            val totalDomains = table.selectAll().toList().size.toLong()

            val directDomains = table.selectAll()
                .where { table.strategy eq BypassStrategy.DIRECT.name }
                .toList()
                .size
                .toLong()

            val proxyDomains = table.selectAll()
                .where { table.strategy eq BypassStrategy.FREE_ROTATING_PROXY.name }
                .toList()
                .size
                .toLong()

            // Domains blocked in last 24 hours
            val twentyFourHoursAgo = Clock.System.now().toEpochMilliseconds() - (24 * 60 * 60 * 1000)
            val blockedLast24h = table.selectAll()
                .where { table.lastBlockedAtEpochMs greaterEq twentyFourHoursAgo }
                .toList()
                .size
                .toLong()

            BypassStrategyStatistics(
                totalDomains = totalDomains,
                directDomains = directDomains,
                proxyDomains = proxyDomains,
                domainsBlockedLast24h = blockedLast24h
            )
        }
    }

    private fun rowToEntity(row: org.jetbrains.exposed.v1.core.ResultRow): DomainBypassStrategy {
        return DomainBypassStrategy(
            id = row[table.id],
            domain = row[table.domain],
            strategy = BypassStrategy.valueOf(row[table.strategy]),
            lastBlockedAt = row[table.lastBlockedAtEpochMs]?.let { Instant.fromEpochMilliseconds(it) },
            lastSuccessAt = row[table.lastSuccessAtEpochMs]?.let { Instant.fromEpochMilliseconds(it) },
            consecutiveFailures = row[table.consecutiveFailures],
            createdAt = Instant.fromEpochMilliseconds(row[table.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[table.updatedAtEpochMs])
        )
    }
}

