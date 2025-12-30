package io.deepsearch.domain.knowledgegraph

import kotlinx.serialization.Serializable

/**
 * Classification of entity types extracted from web content.
 */
@Serializable
enum class EntityType {
    /** Software products, services, or offerings */
    PRODUCT,
    
    /** Named pricing plans (e.g., "Pro Plan", "Enterprise") */
    PRICING_TIER,
    
    /** Product features or capabilities */
    FEATURE,
    
    /** Organizations */
    COMPANY,
    
    /** Named individuals */
    PERSON,
    
    /** Third-party integrations */
    INTEGRATION,
    
    /** Specific dates or time periods */
    DATE,
    
    /** Monetary values with currency */
    PRICE,
    
    /** Technologies, languages, frameworks */
    TECHNOLOGY,
    
    /** Anything else significant */
    OTHER
}

