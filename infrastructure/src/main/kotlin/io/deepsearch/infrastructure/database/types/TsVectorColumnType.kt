package io.deepsearch.infrastructure.database.types

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Custom column type for PostgreSQL tsvector type for full-text search.
 * 
 * The tsvector type is automatically populated by triggers and represents
 * a document in a form optimized for text search. We store it as a string
 * in the application layer, as the actual value is managed by PostgreSQL
 * triggers that call to_tsvector().
 */
class TsVectorColumnType : ColumnType<String>() {
    
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    
    override fun sqlType(): String = "tsvector"
    
    /**
     * Convert database value to Kotlin String.
     * PostgreSQL returns tsvector as a text representation.
     */
    override fun valueFromDB(value: Any): String {
        return when (value) {
            is String -> value
            is ByteArray -> String(value)
            else -> {
                logger.warn("Unexpected tsvector value type: ${value::class.simpleName}")
                value.toString()
            }
        }
    }
    
    /**
     * Convert Kotlin String to database value.
     * Note: In practice, this column is managed by PostgreSQL triggers,
     * so this method typically won't be called for writes.
     */
    override fun notNullValueToDB(value: String): String {
        return value
    }
}

/**
 * Extension function to add a tsvector column to a table.
 * 
 * Usage:
 * ```kotlin
 * val markdownSearchVector = tsvector("markdown_search_vector").nullable()
 * ```
 */
fun Table.tsvector(name: String): Column<String> {
    return registerColumn(name, TsVectorColumnType())
}

