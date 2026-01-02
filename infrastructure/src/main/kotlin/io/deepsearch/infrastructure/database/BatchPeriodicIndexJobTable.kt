package io.deepsearch.infrastructure.database

import io.deepsearch.infrastructure.services.IDatabaseCryptoService
import org.jetbrains.exposed.v1.core.Table

/**
 * Database table for batch periodic index jobs.
 * 
 * Batch jobs use Gemini's Batch API for cost-effective background processing.
 * Unlike interactive periodic index jobs, batch jobs can take up to 24+ hours.
 */
class BatchPeriodicIndexJobTable(
    private val databaseCryptoService: IDatabaseCryptoService
) : Table("batch_periodic_index_jobs") {
    val id = long("id").autoIncrement()
    val userId = integer("user_id")
    val baseUrl = varchar("base_url", length = 2048)
    val maxUrlCount = integer("max_url_count")
    val sitemapUrl = varchar("sitemap_url", length = 2048).nullable()
    val state = varchar("state", length = 64)
    val createdAtMs = long("created_at_ms")
    val updatedAtMs = long("updated_at_ms")
    val version = long("version").default(0)
    val languagePattern = varchar("language_pattern", length = 64).nullable()
    val ocrLanguage = varchar("ocr_language", length = 16)
    
    // Gemini batch job tracking - JSON array of batch job IDs
    // Most stages have 1 ID, Stage 4 has 2 (embedding + KG extraction)
    val batchJobIds = text("batch_job_ids").nullable()
    val batchJobCreatedAtMs = long("batch_job_created_at_ms").nullable()
    val lastResumedAtMs = long("last_resumed_at_ms").nullable()
    
    // Error tracking
    val errorMessage = text("error_message").nullable()
    
    // Progress counters (5 stages now)
    /** Stage 1: URLs that have been crawled + browser extracted */
    val urlsProcessed = integer("urls_processed").default(0)
    /** Stage 2: URLs with content LLM batch complete */
    val urlsContentProcessed = integer("urls_content_processed").default(0)
    /** Stage 3: URLs with final LLM batch complete */
    val urlsFinalProcessed = integer("urls_final_processed").default(0)
    /** Stage 4: URLs written to cache */
    val urlsCached = integer("urls_cached").default(0)

    init {
        index(false, baseUrl)
        index(false, userId)
        index(false, state)
    }

    override val primaryKey = PrimaryKey(id)
}
