package io.deepsearch.infrastructure.database.types

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Custom column type for PostgreSQL vector type using pgvector extension.
 * Supports R2DBC by using text format for vector representation.
 * 
 * Vectors are stored and retrieved in pgvector's text format: "[1.0,2.0,3.0,...]"
 */
class VectorColumnType(private val dimensions: Int) : ColumnType<List<Float>>() {
    
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    
    override fun sqlType(): String = "vector($dimensions)"
    
    /**
     * Convert database value to Kotlin List<Float>.
     * R2DBC returns vectors as strings in format "[1.0,2.0,3.0,...]"
     */
    override fun valueFromDB(value: Any): List<Float> {
        return when (value) {
            is String -> parseVectorString(value)
            is ByteArray -> parseVectorString(String(value))
            else -> {
                logger.error("Unexpected vector value type: ${value::class.simpleName}")
                throw IllegalArgumentException("Cannot convert ${value::class.simpleName} to vector")
            }
        }
    }
    
    /**
     * Convert Kotlin List<Float> to database value.
     * Returns vector in text format for R2DBC: "[1.0,2.0,3.0,...]"
     */
    override fun notNullValueToDB(value: List<Float>): String {
        require(value.size == dimensions) {
            "Vector dimension mismatch: expected $dimensions, got ${value.size}"
        }
        return "[${value.joinToString(",")}]"
    }
    
    /**
     * Parse vector from pgvector's text format.
     * Input format: "[1.0,2.0,3.0,...]" or "1.0,2.0,3.0,..."
     */
    private fun parseVectorString(str: String): List<Float> {
        val cleaned = str.trim().removeSurrounding("[", "]")
        if (cleaned.isEmpty()) {
            return emptyList()
        }
        return cleaned.split(",").map { it.trim().toFloat() }
    }
}

/**
 * Extension function to add a vector column to a table.
 * 
 * Usage:
 * ```kotlin
 * val embedding = vector("embedding", 1536)
 * ```
 */
fun Table.vector(name: String, dimensions: Int): Column<List<Float>> {
    return registerColumn(name, VectorColumnType(dimensions))
}

