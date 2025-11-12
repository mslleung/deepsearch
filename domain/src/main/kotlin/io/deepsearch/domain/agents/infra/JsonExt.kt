package io.deepsearch.domain.agents.infra

import io.deepsearch.domain.exceptions.LlmDeserializationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Extension functions for JSON extraction from LLM responses that may contain code blocks.
 */

/**
 * Extracts JSON from a response that may contain ```json``` code blocks.
 * First tries to parse the response as-is, then attempts to extract JSON from code blocks.
 * 
 * @throws LlmDeserializationException if JSON content cannot be extracted or parsed
 */
inline fun <reified T> Json.decodeFromStringWithCodeBlocks(response: String): T {
    try {
        // Try to extract JSON from ```json``` code blocks using regex
        val jsonPattern = Regex("^```json\\n([\\s\\S]*?)\\n```$|^([\\s\\S]*)$", RegexOption.IGNORE_CASE)
        val match = jsonPattern.find(response)
        val jsonContent = match?.let { matchResult ->
            // Try group 1 first (```json``` code block), then group 2 (entire response)
            matchResult.groupValues[1].takeIf { it.isNotBlank() } 
                ?: matchResult.groupValues[2].takeIf { it.isNotBlank() }
        }?.trim()

        if (jsonContent.isNullOrBlank()) {
            throw LlmDeserializationException(
                "No JSON content found in LLM response.",
                IllegalStateException("Response was blank or null")
            )
        }

        return decodeFromString<T>(jsonContent)
    } catch (e: SerializationException) {
        throw LlmDeserializationException("Failed to parse LLM response as JSON: ${e.message}", e)
    } catch (e: LlmDeserializationException) {
        // Re-throw LlmDeserializationException as-is
        throw e
    } catch (e: Exception) {
        throw LlmDeserializationException("Unexpected error deserializing LLM response: ${e.message}", e)
    }
}
