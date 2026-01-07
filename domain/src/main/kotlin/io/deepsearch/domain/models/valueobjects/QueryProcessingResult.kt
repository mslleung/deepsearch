package io.deepsearch.domain.models.valueobjects

/**
 * Result of query processing that includes context-aware query expansion
 * and explicit fulfillment requirements.
 * 
 * Used by downstream agents (eval + synthesis) to make better completion decisions.
 */
data class QueryProcessingResult(
    /** The original raw query from the user */
    val originalQuery: String,
    
    /** 
     * Query expanded with website context for clarity.
     * Example: "What's the pricing?" -> "What are Stripe's pricing and fees for payment processing?"
     */
    val expandedQuery: String,
    
    /**
     * Atomic fulfillment requirements that must ALL be satisfied for the query to be complete.
     * Example: ["Transaction fees", "Monthly fees", "Volume discounts", "Enterprise pricing"]
     */
    val fulfillmentRequirements: List<String>,
    
    /**
     * Follow-up queries for early link discovery.
     * These are emitted to the discovery channel immediately after query processing.
     */
    val followUpQueries: List<String> = emptyList(),
    
    /** The website context used to generate this result */
    val websiteContext: WebsiteContext
)

