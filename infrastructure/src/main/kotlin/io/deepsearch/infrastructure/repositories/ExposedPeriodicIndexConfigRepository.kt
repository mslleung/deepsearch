package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.PeriodicIndexConfig
import io.deepsearch.domain.models.valueobjects.OcrLanguage
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.repositories.IPeriodicIndexConfigRepository
import io.deepsearch.infrastructure.database.PeriodicIndexConfigTable
import io.deepsearch.infrastructure.services.ITransactionService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update

class ExposedPeriodicIndexConfigRepository(
    private val periodicIndexConfigTable: PeriodicIndexConfigTable,
    private val transactionService: ITransactionService
) : IPeriodicIndexConfigRepository {

    override suspend fun create(config: PeriodicIndexConfig): PeriodicIndexConfig = transactionService.withTransaction {
        val id = periodicIndexConfigTable.insert {
            it[userId] = config.userId.value
            it[url] = config.url
            it[sitemapUrl] = config.sitemapUrl
            it[periodDays] = config.periodDays
            it[maxUrlCount] = config.maxUrlCount
            it[enabled] = config.enabled
            it[createdAt] = config.createdAt
            it[updatedAt] = config.updatedAt
            it[lastRunAt] = config.lastRunAt
            it[version] = config.version
            it[languagePattern] = config.languagePattern
            it[ocrLanguage] = config.ocrLanguage.code
        }[periodicIndexConfigTable.id]

        PeriodicIndexConfig(
            id = id,
            userId = config.userId,
            url = config.url,
            sitemapUrl = config.sitemapUrl,
            periodDays = config.periodDays,
            maxUrlCount = config.maxUrlCount,
            enabled = config.enabled,
            createdAt = config.createdAt,
            updatedAt = config.updatedAt,
            lastRunAt = config.lastRunAt,
            version = config.version,
            languagePattern = config.languagePattern,
            ocrLanguage = config.ocrLanguage
        )
    }

    override suspend fun update(config: PeriodicIndexConfig): PeriodicIndexConfig = transactionService.withTransaction {
        val id = config.id ?: throw IllegalArgumentException("Cannot update config without ID")
        val rows = periodicIndexConfigTable.update({ periodicIndexConfigTable.id eq id }) {
            it[userId] = config.userId.value
            it[url] = config.url
            it[sitemapUrl] = config.sitemapUrl
            it[periodDays] = config.periodDays
            it[maxUrlCount] = config.maxUrlCount
            it[enabled] = config.enabled
            it[updatedAt] = config.updatedAt
            it[lastRunAt] = config.lastRunAt
            it[version] = config.version + 1
            it[languagePattern] = config.languagePattern
            it[ocrLanguage] = config.ocrLanguage.code
        }
        if (rows > 0) {
            config.apply { version += 1 }
        } else {
            throw IllegalStateException("PeriodicIndexConfig not found or version mismatch")
        }
    }

    override suspend fun findById(id: Long): PeriodicIndexConfig? = transactionService.withTransaction {
        periodicIndexConfigTable.selectAll()
            .where { periodicIndexConfigTable.id eq id }
            .map { toDomain(it) }
            .singleOrNull()
    }

    override suspend fun findAllByUserId(userId: UserId): List<PeriodicIndexConfig> = transactionService.withTransaction {
        periodicIndexConfigTable.selectAll()
            .where { periodicIndexConfigTable.userId eq userId.value }
            .map { toDomain(it) }
            .toList()
    }

    override suspend fun countByUserId(userId: UserId): Int = transactionService.withTransaction {
        periodicIndexConfigTable.selectAll()
            .where { periodicIndexConfigTable.userId eq userId.value }
            .count()
            .toInt()
    }

    override suspend fun findEnabledConfigs(): List<PeriodicIndexConfig> = transactionService.withTransaction {
        periodicIndexConfigTable.selectAll()
            .where { periodicIndexConfigTable.enabled eq true }
            .map { toDomain(it) }
            .toList()
    }

    override suspend fun delete(config: PeriodicIndexConfig) = transactionService.withTransaction {
        val id = config.id ?: throw IllegalArgumentException("Cannot delete config without ID")
        periodicIndexConfigTable.deleteWhere { periodicIndexConfigTable.id eq id }
        Unit
    }

    private fun toDomain(row: ResultRow): PeriodicIndexConfig {
        return PeriodicIndexConfig(
            id = row[periodicIndexConfigTable.id],
            userId = UserId(row[periodicIndexConfigTable.userId]),
            url = row[periodicIndexConfigTable.url],
            sitemapUrl = row[periodicIndexConfigTable.sitemapUrl],
            periodDays = row[periodicIndexConfigTable.periodDays],
            maxUrlCount = row[periodicIndexConfigTable.maxUrlCount],
            enabled = row[periodicIndexConfigTable.enabled],
            createdAt = row[periodicIndexConfigTable.createdAt],
            updatedAt = row[periodicIndexConfigTable.updatedAt],
            lastRunAt = row[periodicIndexConfigTable.lastRunAt],
            version = row[periodicIndexConfigTable.version],
            languagePattern = row[periodicIndexConfigTable.languagePattern],
            ocrLanguage = OcrLanguage.fromCodeOrDefault(row[periodicIndexConfigTable.ocrLanguage])
        )
    }
}
