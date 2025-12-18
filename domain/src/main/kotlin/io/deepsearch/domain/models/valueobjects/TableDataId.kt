package io.deepsearch.domain.models.valueobjects

/**
 * Typed identifier for table elements (data-ds-id attribute value).
 */
@JvmInline
value class TableDataId(val value: String) {
    init {
        require(value.isNotBlank()) { "Table data ID must not be blank" }
    }
}

