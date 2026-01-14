package io.deepsearch.domain.models.valueobjects

/**
 * Represents the cumulative history of a session chain for query continuation.
 * Contains summaries of all prior sessions in chronological order (oldest first).
 * 
 * This enables the search pipeline to:
 * - Understand what topics have been explored
 * - Know what knowledge baseline exists
 * - Generate delta requirements (what's new vs what's covered)
 * - Avoid repeating information across the chain
 */
data class SessionHistory(
    val sessions: List<SessionSummary>
) {
    /**
     * Summary of a single session in the history chain.
     * Contains enough context for downstream agents to understand
     * what was asked, what was answered, and what requirements were covered.
     */
    data class SessionSummary(
        val sessionId: String,
        val query: String,
        val answer: String
    )
    
    /**
     * The ID of the first session in this chain (the root).
     * Used when creating a new session to set its rootSessionId for O(1) history loading.
     * Returns null if the history is empty.
     */
    val rootSessionId: QuerySessionId?
        get() = sessions.firstOrNull()?.let { QuerySessionId(it.sessionId) }
    
    /**
     * The ID of the most recent session in this chain (the one being continued).
     * Used when creating a new session to link it to its predecessor.
     * Returns null if the history is empty.
     */
    val previousSessionId: QuerySessionId?
        get() = sessions.lastOrNull()?.let { QuerySessionId(it.sessionId) }
    
    /**
     * Get all prior queries in the chain (for deduplication).
     */
    fun getAllPriorQueries(): List<String> = sessions.map { it.query }
    
    /**
     * Get a formatted summary of the session history for LLM prompts.
     * Presents sessions in chronological order with clear separation.
     */
    fun toPromptSummary(): String = buildString {
        sessions.forEachIndexed { index, session ->
            appendLine("## Session ${index + 1}")
            appendLine("Query: ${session.query}")
            appendLine()
            appendLine("Answer:")
            appendLine(session.answer)
            if (index < sessions.lastIndex) {
                appendLine()
                appendLine("---")
                appendLine()
            }
        }
    }
    
    /**
     * Check if the history is empty (no prior sessions).
     */
    fun isEmpty(): Boolean = sessions.isEmpty()
    
    /**
     * Check if the history has any prior sessions.
     */
    fun isNotEmpty(): Boolean = sessions.isNotEmpty()
    
    companion object {
        /**
         * Create an empty session history (no prior context).
         */
        fun empty(): SessionHistory = SessionHistory(emptyList())
    }
}
