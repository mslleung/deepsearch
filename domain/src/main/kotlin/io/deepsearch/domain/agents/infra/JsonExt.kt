package io.deepsearch.domain.agents.infra

import kotlinx.serialization.json.Json

/**
 * Extension functions for JSON extraction from LLM responses that may contain code blocks.
 */

/**
 * Extracts JSON from a response that may contain ```json``` code blocks.
 * First tries to parse the response as-is, then attempts to extract JSON from code blocks.
 */
inline fun <reified T> Json.decodeFromStringWithCodeBlocks(response: String): T {
    // Try to extract JSON from ```json``` code blocks using regex
    val jsonPattern = Regex("^```json\\n([\\s\\S]*?)\\n```$|^([\\s\\S]*)$", RegexOption.IGNORE_CASE)
    val match = jsonPattern.find(response)
    val jsonContent = match?.let { matchResult ->
        // Try group 1 first (```json``` code block), then group 2 (entire response)
        matchResult.groupValues[1].takeIf { it.isNotBlank() } 
            ?: matchResult.groupValues[2].takeIf { it.isNotBlank() }
    }?.trim()

    if (jsonContent.isNullOrBlank()) {
        throw Error("No JSON content found")
    }

    return decodeFromString<T>(jsonContent)
}
