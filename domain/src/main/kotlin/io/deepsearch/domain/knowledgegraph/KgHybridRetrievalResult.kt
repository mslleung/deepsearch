package io.deepsearch.domain.knowledgegraph

import kotlinx.serialization.Serializable

/**
 * Sources that contributed to the retrieval result.
 */
@Serializable
data class RetrievalSources(
    val hybridSearchUsed: Boolean,
    val graphTraversalUsed: Boolean,
    val textToCypherUsed: Boolean,
    val textToCypherQuery: String? = null
)

/**
 * Result of hybrid KG retrieval combining semantic search, graph traversal, and text-to-cypher.
 * 
 * @property subgraph Subgraph retrieved from semantic entity search + graph traversal
 * @property cypherResults Results from text-to-cypher query execution
 * @property sources Which retrieval strategies contributed to the result
 */
@Serializable
data class KgHybridRetrievalResult(
    val subgraph: KgSubgraph?,
    val cypherResults: List<Map<String, String>>?,
    val sources: RetrievalSources
) {
    /**
     * Check if the result has any meaningful data.
     */
    fun hasResults(): Boolean {
        return (subgraph?.isNotEmpty() == true) || !cypherResults.isNullOrEmpty()
    }
    
    /**
     * Get all unique source URLs from the subgraph entities.
     */
    fun getAllSourceUrls(): Set<String> {
        return subgraph?.entities?.flatMap { it.sourceUrls }?.toSet() ?: emptySet()
    }
    
    /**
     * Get all facts from the subgraph entities.
     */
    fun getAllFacts(): List<String> {
        return subgraph?.entities?.flatMap { it.facts } ?: emptyList()
    }
    
    companion object {
        fun empty() = KgHybridRetrievalResult(
            subgraph = null,
            cypherResults = null,
            sources = RetrievalSources(
                hybridSearchUsed = false,
                graphTraversalUsed = false,
                textToCypherUsed = false
            )
        )
    }
}

