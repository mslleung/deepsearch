package io.deepsearch.infrastructure.database

import io.deepsearch.infrastructure.services.IDatabaseCryptoService
import org.jetbrains.exposed.v1.core.Table

/**
 * Join table tracking URL-to-image relationships.
 * Tracks which images are present on which URLs and whether they are still active
 * (found in the latest extraction of that URL).
 */
class WebpageImageLinkageTable(
    private val databaseCryptoService: IDatabaseCryptoService,
    private val webpageImageCacheTable: WebpageImageCacheTable
) : Table("webpage_image_linkages") {
    val url = varchar("url", length = 2048)
    val imageBytesHash = varchar("image_bytes_hash", length = 128)
        .references(webpageImageCacheTable.imageBytesHash)
    val isActive = bool("is_active").default(true)
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")

    init {
        // Index on URL for efficient lookups of all images on a page
        index(false, url)
        // Index on image hash for finding all pages containing an image
        index(false, imageBytesHash)
    }

    override val primaryKey = PrimaryKey(url, imageBytesHash)
}


