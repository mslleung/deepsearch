package io.deepsearch.infrastructure.database.types

import io.r2dbc.postgresql.codec.Vector
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Custom column type for PostgreSQL vector type using pgvector extension.
 * Supports R2DBC with r2dbc-postgresql 1.0.3+.
 * 
 * - Encoding (write): Uses string format "[1.0,2.0,...]" which PostgreSQL implicitly casts to vector.
 *   R2DBC PostgreSQL doesn't have a built-in encoder for the Vector type.
 * - Decoding (read): Uses io.r2dbc.postgresql.codec.Vector which R2DBC PostgreSQL natively returns.
 * 
 * See: https://github.com/pgjdbc/r2dbc-postgresql#data-type-mapping
 */
class VectorColumnType(private val dimensions: Int) : ColumnType<List<Float>>() {
    
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    
    override fun sqlType(): String = "vector($dimensions)"
    
    /**
     * Convert database value to Kotlin List<Float>.
     * R2DBC PostgreSQL returns vectors as io.r2dbc.postgresql.codec.Vector objects.
     * Also supports legacy string and byte array formats for compatibility.
     */
    override fun valueFromDB(value: Any): List<Float> {
        return when (value) {
            is Vector -> value.getVector().toList()  // R2DBC PostgreSQL native Vector type
            is FloatArray -> value.toList()
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
     * Returns a String in pgvector format "[1.0,2.0,3.0,...]" which PostgreSQL
     * implicitly casts to the vector type. R2DBC PostgreSQL doesn't have a built-in
     * encoder for the Vector type, so we use string representation instead.
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

