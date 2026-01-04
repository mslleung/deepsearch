package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

/**
 * Database table for storing markdown indexing tasks.
 * 
 * Tasks are created when markdown is cached and processed asynchronously
 * by background workers. Supports two task types:
 * - EMBEDDING: Generate vector embeddings for hybrid search
 * - KNOWLEDGE_GRAPH: Extract entities and relationships
 */
class MarkdownIndexingTaskTable : Table("markdown_indexing_tasks") {
    val id = long("id").autoIncrement()
    
    /** URL of the webpage being indexed */
    val url = varchar("url", length = 2048)
    
    /** Task type: EMBEDDING or KNOWLEDGE_GRAPH */
    val taskType = varchar("task_type", length = 32)
    
    /** Session ID for token usage tracking */
    val sessionId = varchar("session_id", length = 128)
    
    /** Current status: PENDING, IN_PROGRESS, COMPLETED, FAILED */
    val status = varchar("status", length = 32).default("PENDING")
    
    /** Markdown content to process */
    val markdown = text("markdown")
    
    /** Number of processing attempts */
    val attempts = integer("attempts").default(0)
    
    /** Maximum retry attempts before marking as failed */
    val maxAttempts = integer("max_attempts").default(3)
    
    /** Error message if task failed */
    val errorMessage = text("error_message").nullable()
    
    /** When the task was created (epoch ms) */
    val createdAtMs = long("created_at_ms")
    
    /** When the task was last updated (epoch ms) */
    val updatedAtMs = long("updated_at_ms")
    
    /** When a worker claimed this task (epoch ms, null if not claimed) */
    val claimedAtMs = long("claimed_at_ms").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        // Index for efficient task claiming: find pending tasks by type
        index(false, status, taskType)
        // Index for lookups by URL
        index(false, url)
    }
}

