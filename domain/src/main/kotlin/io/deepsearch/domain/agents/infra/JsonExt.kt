package io.deepsearch.domain.agents.infra

import com.google.genai.errors.ClientException
import io.deepsearch.domain.exceptions.LlmDeserializationException
import io.deepsearch.domain.exceptions.LlmRateLimitException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Extension functions for JSON extraction from LLM responses that may contain code blocks.
 */

/** Regex to parse retry delay from Google GenAI rate limit error messages */
@PublishedApi
internal val RETRY_DELAY_REGEX = Regex("""Please retry in ([\d.]+)s""")

/** Base delay in milliseconds for exponential backoff when parsing fails */
@PublishedApi
internal const val BASE_BACKOFF_MS = 30_000L

/** Maximum number of rate limit retries */
@PublishedApi
internal const val MAX_RATE_LIMIT_RETRIES = 3

/**
 * Parses the retry delay from a rate limit error message, or returns an exponential backoff delay.
 * 
 * @param message The error message to parse
 * @param attempt The current retry attempt (0-indexed)
 * @return The delay in milliseconds to wait before retrying
 */
@PublishedApi
internal fun parseRetryDelayOrDefault(message: String?, attempt: Int): Long {
    if (message == null) {
        return BASE_BACKOFF_MS * (1 shl attempt)
    }
    
    val match = RETRY_DELAY_REGEX.find(message)
    return if (match != null) {
        val seconds = match.groupValues[1].toDoubleOrNull() ?: 30.0
        // Add 1 second buffer and convert to milliseconds
        ((seconds + 1.0) * 1000).toLong()
    } else {
        // Exponential backoff: 30s, 60s, 120s...
        BASE_BACKOFF_MS * (1 shl attempt)
    }
}

/**
 * Checks if an exception is a rate limit (429) error from Google GenAI.
 */
@PublishedApi
internal fun isRateLimitException(e: Exception): Boolean {
    return e is ClientException && e.message?.contains("429") == true
}

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

/**
 * Result of a single LLM call attempt.
 */
@PublishedApi
internal sealed class LlmCallResult<out T> {
    data class Success<T>(val value: T) : LlmCallResult<T>()
    data class RateLimited(val exception: Exception) : LlmCallResult<Nothing>()
    data class DeserializationFailed(val exception: LlmDeserializationException) : LlmCallResult<Nothing>()
    data class OtherError(val exception: Exception) : LlmCallResult<Nothing>()
}

/**
 * Retries an LLM call with handling for both rate limits and deserialization failures.
 * 
 * Rate limit handling:
 * - Detects 429 errors from Google GenAI ClientException
 * - Parses retry delay from error message ("Please retry in X.Xs")
 * - Falls back to exponential backoff if parsing fails
 * - Max rate limit retries: 3
 * 
 * @param maxDeserializationRetries Maximum number of attempts for deserialization failures (default 1)
 * @param llmCall Lambda that performs the LLM call and returns the response string
 * @return Deserialized response of type T
 * @throws LlmDeserializationException if all deserialization retries fail
 * @throws LlmRateLimitException if all rate limit retries are exhausted
 */
suspend inline fun <reified T> retryLlmCall(
    maxDeserializationRetries: Int = 1,
    crossinline llmCall: suspend () -> String
): T {
    val logger = LoggerFactory.getLogger("io.deepsearch.domain.agents.infra.JsonExt")
    var rateLimitAttempt = 0

    while (true) {
        // Try the LLM call with deserialization retries
        val result = tryLlmCallWithDeserializationRetries<T>(maxDeserializationRetries, logger, llmCall)
        
        when (result) {
            is LlmCallResult.Success -> return result.value
            
            is LlmCallResult.DeserializationFailed -> throw result.exception
            
            is LlmCallResult.OtherError -> throw result.exception
            
            is LlmCallResult.RateLimited -> {
                rateLimitAttempt++
                if (rateLimitAttempt > MAX_RATE_LIMIT_RETRIES) {
                    logger.error("Rate limit retries exhausted after $MAX_RATE_LIMIT_RETRIES attempts")
                    throw LlmRateLimitException(MAX_RATE_LIMIT_RETRIES, result.exception)
                }
                
                val delayMs = parseRetryDelayOrDefault(result.exception.message, rateLimitAttempt - 1)
                logger.warn(
                    "Rate limited (429) on attempt $rateLimitAttempt/$MAX_RATE_LIMIT_RETRIES. " +
                    "Waiting ${delayMs}ms before retry."
                )
                delay(delayMs)
                // Continue to next iteration
            }
        }
    }
}

/**
 * Attempts the LLM call with deserialization retries.
 * Returns a result indicating success, rate limit, deserialization failure, or other error.
 */
@PublishedApi
internal suspend inline fun <reified T> tryLlmCallWithDeserializationRetries(
    maxRetries: Int,
    logger: org.slf4j.Logger,
    crossinline llmCall: suspend () -> String
): LlmCallResult<T> {
    var lastDeserializationException: LlmDeserializationException? = null
    
    repeat(maxRetries) { attempt ->
        try {
            val response = llmCall()
            logger.debug("Decoding LLM response: {}", response)
            return LlmCallResult.Success(Json.decodeFromStringWithCodeBlocks<T>(response))
        } catch (e: LlmDeserializationException) {
            lastDeserializationException = e
            val attemptNum = attempt + 1
            if (attemptNum < maxRetries) {
                logger.warn("LLM deserialization failed on attempt $attemptNum/$maxRetries: ${e.message}. Retrying...")
            } else {
                logger.error("LLM deserialization failed after $maxRetries attempts: ${e.message}")
            }
        } catch (e: Exception) {
            if (isRateLimitException(e)) {
                return LlmCallResult.RateLimited(e)
            }
            return LlmCallResult.OtherError(e)
        }
    }
    
    return LlmCallResult.DeserializationFailed(lastDeserializationException!!)
}
