package io.deepsearch.domain.models.valueobjects

import kotlinx.serialization.Serializable

/**
 * Classification of how directly a source answers the query.
 * Indicates the quality and explicitness of the information provided.
 */
@Serializable
enum class AnswerType {
    /**
     * The page explicitly lists the answer (e.g., a pricing table).
     * Information is directly stated and readily available.
     */
    DIRECT_ANSWER,

    /**
     * The answer must be guessed or calculated from the information provided.
     * Requires interpretation or computation to derive the answer.
     */
    INFERRED_ANSWER,

    /**
     * Mentions keywords but doesn't answer the core intent.
     * Tangentially related but not directly answering the query.
     */
    PARTIAL_MENTION
}


