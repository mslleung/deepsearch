package io.deepsearch.domain.knowledgegraph

import kotlinx.serialization.Serializable

/**
 * Classification of relationship types between entities.
 */
@Serializable
enum class RelationType {
    /** Entity has a price (e.g., Pro Plan → $79/month) */
    HAS_PRICE,
    
    /** Entity has a feature */
    HAS_FEATURE,
    
    /** Entity includes another (e.g., Enterprise includes SSO) */
    INCLUDES,
    
    /** Entity is part of another */
    PART_OF,
    
    /** Integration relationship */
    INTEGRATES_WITH,
    
    /** Competitor relationship */
    COMPETES_WITH,
    
    /** Ownership relationship */
    OWNED_BY,
    
    /** Release date relationship */
    RELEASED_ON,
    
    /** Update date relationship */
    UPDATED_ON,
    
    /** Newer version replaces older */
    SUPERSEDES,
    
    /** General relationship */
    RELATED_TO
}

