package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

class SitemapCacheTable : Table("sitemap_cache") {
    val sitemapUrl = varchar("sitemap_url", length = 2048)
    val xmlContent = text("xml_content")
    val linksJson = text("links_json")
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")

    init {
        // Unique index on sitemap URL
        index(true, sitemapUrl)
    }

    override val primaryKey = PrimaryKey(sitemapUrl)
}

