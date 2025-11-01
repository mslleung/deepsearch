package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.SitemapCache
import io.deepsearch.domain.repositories.ISitemapCacheRepository
import io.deepsearch.infrastructure.database.SitemapCacheTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.upsert
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedSitemapCacheRepository(
    private val sitemapCacheTable: SitemapCacheTable
) : ISitemapCacheRepository {

    override suspend fun findByUrl(sitemapUrl: String): SitemapCache? = suspendTransaction {
        sitemapCacheTable.selectAll()
            .where { sitemapCacheTable.sitemapUrl eq sitemapUrl }
            .map { mapRowToSitemapCache(it) }
            .singleOrNull()
    }

    override suspend fun upsert(cache: SitemapCache): Unit = suspendTransaction {
        sitemapCacheTable.upsert(
            keys = arrayOf(sitemapCacheTable.sitemapUrl)
        ) {
            it[sitemapUrl] = cache.sitemapUrl
            it[xmlContent] = cache.xmlContent
            it[linksJson] = Json.encodeToString(cache.links)
            it[createdAtEpochMs] = cache.createdAt.toEpochMilliseconds()
            it[updatedAtEpochMs] = cache.updatedAt.toEpochMilliseconds()
        }
    }

    private fun mapRowToSitemapCache(row: ResultRow): SitemapCache {
        val linksJson = row[sitemapCacheTable.linksJson]
        val links = Json.decodeFromString<List<String>>(linksJson)
        
        return SitemapCache(
            sitemapUrl = row[sitemapCacheTable.sitemapUrl],
            xmlContent = row[sitemapCacheTable.xmlContent],
            links = links,
            createdAt = Instant.fromEpochMilliseconds(row[sitemapCacheTable.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[sitemapCacheTable.updatedAtEpochMs])
        )
    }
}

