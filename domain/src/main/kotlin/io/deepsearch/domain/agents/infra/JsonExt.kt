package io.deepsearch.domain.agents.infra

import com.google.genai.errors.ClientException
import com.openai.errors.RateLimitException
import io.deepsearch.domain.exceptions.LlmDeserializationException
import io.deepsearch.domain.exceptions.LlmRateLimitException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
 * Checks if an exception is a rate limit (429) error from either Google GenAI or the OpenAI SDK.
 */
@PublishedApi
internal fun isRateLimitException(e: Exception): Boolean {
    return (e is ClientException && e.message?.contains("429") == true) ||
        e is RateLimitException
}

/**
 * Wraps any Gemini API call with rate limit retry handling.
 * 
 * This is a simpler version of [retryLlmCall] for API calls that don't require
 * JSON deserialization (e.g., tool-based agents, embeddings, file operations).
 * 
 * Rate limit handling:
 * - Detects 429 errors from Google GenAI ClientException
 * - Parses retry delay from error message ("Please retry in X.Xs")
 * - Falls back to exponential backoff if parsing fails
 * - Max rate limit retries: 3
 * 
 * @param operationName Name of the operation (for logging)
 * @param block The suspend function to execute with retry handling
 * @return The result of the block
 * @throws LlmRateLimitException if all rate limit retries are exhausted
 */
suspend fun <T> withRateLimitRetry(
    operationName: String,
    block: suspend () -> T
): T {
    val logger = LoggerFactory.getLogger("io.deepsearch.domain.agents.infra.JsonExt")
    var rateLimitAttempt = 0
    
    while (true) {
        // Check for cancellation before each attempt (enables fast cancellation)
        currentCoroutineContext().ensureActive()
        
        try {
            val result = block()
            // Check for cancellation after the call completes
            currentCoroutineContext().ensureActive()
            return result
        } catch (e: Exception) {
            if (isRateLimitException(e)) {
                rateLimitAttempt++
                if (rateLimitAttempt > MAX_RATE_LIMIT_RETRIES) {
                    logger.error("[{}] Rate limit retries exhausted after {} attempts", operationName, MAX_RATE_LIMIT_RETRIES)
                    throw LlmRateLimitException(MAX_RATE_LIMIT_RETRIES, e)
                }
                
                val delayMs = parseRetryDelayOrDefault(e.message, rateLimitAttempt - 1)
                logger.warn(
                    "[{}] Rate limited (429) on attempt {}/{}. Waiting {}ms before retry.",
                    operationName, rateLimitAttempt, MAX_RATE_LIMIT_RETRIES, delayMs
                )
                delay(delayMs)
                // Continue to next iteration
            } else {
                throw e
            }
        }
    }
}

/**
 * Wraps a streaming Gemini API call with rate limit retry handling.
 * 
 * If a 429 error occurs during stream iteration, the entire stream is restarted
 * from the beginning after waiting for the appropriate delay.
 * 
 * Rate limit handling:
 * - Detects 429 errors from Google GenAI ClientException
 * - Parses retry delay from error message ("Please retry in X.Xs")
 * - Falls back to exponential backoff if parsing fails
 * - Max rate limit retries: 3
 * 
 * @param operationName Name of the operation (for logging)
 * @param streamFactory Factory function that creates the stream to iterate
 * @param processItem Function to process each item from the stream
 * @throws LlmRateLimitException if all rate limit retries are exhausted
 */
suspend fun <T, R> withStreamingRateLimitRetry(
    operationName: String,
    streamFactory: suspend () -> Iterable<T>,
    processItem: suspend (T) -> R?
): List<R> {
    val logger = LoggerFactory.getLogger("io.deepsearch.domain.agents.infra.JsonExt")
    var rateLimitAttempt = 0
    
    while (true) {
        // Check for cancellation before each attempt (enables fast cancellation)
        currentCoroutineContext().ensureActive()
        
        try {
            val results = mutableListOf<R>()
            val stream = streamFactory()
            for (item in stream) {
                // Check for cancellation during stream iteration
                currentCoroutineContext().ensureActive()
                val result = processItem(item)
                if (result != null) {
                    results.add(result)
                }
            }
            return results
        } catch (e: Exception) {
            if (isRateLimitException(e)) {
                rateLimitAttempt++
                if (rateLimitAttempt > MAX_RATE_LIMIT_RETRIES) {
                    logger.error("[{}] Rate limit retries exhausted after {} attempts", operationName, MAX_RATE_LIMIT_RETRIES)
                    throw LlmRateLimitException(MAX_RATE_LIMIT_RETRIES, e)
                }
                
                val delayMs = parseRetryDelayOrDefault(e.message, rateLimitAttempt - 1)
                logger.warn(
                    "[{}] Rate limited (429) during streaming on attempt {}/{}. Restarting stream after {}ms.",
                    operationName, rateLimitAttempt, MAX_RATE_LIMIT_RETRIES, delayMs
                )
                delay(delayMs)
                // Continue to next iteration - stream will be recreated
            } else {
                throw e
            }
        }
    }
}

/**
 * Wraps a streaming Gemini API call that emits to a Flow with rate limit retry handling.
 * 
 * If a 429 error occurs during stream iteration, the entire stream is restarted
 * from the beginning after waiting for the appropriate delay.
 * 
 * @param operationName Name of the operation (for logging)
 * @param streamFactory Factory function that creates the stream to iterate
 * @return A Flow that emits processed items with rate limit retry handling
 * @throws LlmRateLimitException if all rate limit retries are exhausted
 */
fun <T> flowWithRateLimitRetry(
    operationName: String,
    streamFactory: suspend () -> Iterable<T>
): Flow<T> = flow {
    val logger = LoggerFactory.getLogger("io.deepsearch.domain.agents.infra.JsonExt")
    var rateLimitAttempt = 0
    
    while (true) {
        // Check for cancellation before each attempt (enables fast cancellation)
        currentCoroutineContext().ensureActive()
        
        try {
            val stream = streamFactory()
            for (item in stream) {
                // Check for cancellation before emitting each item
                currentCoroutineContext().ensureActive()
                emit(item)
            }
            return@flow // Successfully completed
        } catch (e: Exception) {
            if (isRateLimitException(e)) {
                rateLimitAttempt++
                if (rateLimitAttempt > MAX_RATE_LIMIT_RETRIES) {
                    logger.error("[{}] Rate limit retries exhausted after {} attempts", operationName, MAX_RATE_LIMIT_RETRIES)
                    throw LlmRateLimitException(MAX_RATE_LIMIT_RETRIES, e)
                }
                
                val delayMs = parseRetryDelayOrDefault(e.message, rateLimitAttempt - 1)
                logger.warn(
                    "[{}] Rate limited (429) during streaming on attempt {}/{}. Restarting stream after {}ms.",
                    operationName, rateLimitAttempt, MAX_RATE_LIMIT_RETRIES, delayMs
                )
                delay(delayMs)
                // Continue to next iteration - stream will be recreated
            } else {
                throw e
            }
        }
    }
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
 * @param agentName Name of the agent making the call (for logging)
 * @param maxDeserializationRetries Maximum number of attempts for deserialization failures (default 1)
 * @param llmCall Lambda that performs the LLM call and returns the response string
 * @return Deserialized response of type T
 * @throws LlmDeserializationException if all deserialization retries fail
 * @throws LlmRateLimitException if all rate limit retries are exhausted
 */
suspend inline fun <reified T> retryLlmCall(
    agentName: String,
    maxDeserializationRetries: Int = 1,
    crossinline llmCall: suspend () -> String
): T {
    val logger = LoggerFactory.getLogger("io.deepsearch.domain.agents.infra.JsonExt")
    var rateLimitAttempt = 0

    while (true) {
        // Check for cancellation before each attempt (enables fast cancellation)
        currentCoroutineContext().ensureActive()
        
        // Try the LLM call with deserialization retries
        val result = tryLlmCallWithDeserializationRetries<T>(agentName, maxDeserializationRetries, logger, llmCall)
        
        // Check for cancellation after the call completes
        currentCoroutineContext().ensureActive()
        
        when (result) {
            is LlmCallResult.Success -> return result.value
            
            is LlmCallResult.DeserializationFailed -> throw result.exception
            
            is LlmCallResult.OtherError -> throw result.exception
            
            is LlmCallResult.RateLimited -> {
                rateLimitAttempt++
                if (rateLimitAttempt > MAX_RATE_LIMIT_RETRIES) {
                    logger.error("[{}] Rate limit retries exhausted after {} attempts", agentName, MAX_RATE_LIMIT_RETRIES)
                    throw LlmRateLimitException(MAX_RATE_LIMIT_RETRIES, result.exception)
                }
                
                val delayMs = parseRetryDelayOrDefault(result.exception.message, rateLimitAttempt - 1)
                logger.warn(
                    "[{}] Rate limited (429) on attempt {}/{}. Waiting {}ms before retry.",
                    agentName, rateLimitAttempt, MAX_RATE_LIMIT_RETRIES, delayMs
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
    agentName: String,
    maxRetries: Int,
    logger: org.slf4j.Logger,
    crossinline llmCall: suspend () -> String
): LlmCallResult<T> {
    var lastDeserializationException: LlmDeserializationException? = null
    
    repeat(maxRetries) { attempt ->
        // Check for cancellation before each retry attempt
        currentCoroutineContext().ensureActive()
        
        try {
            val response = llmCall()
            
            // Check for cancellation after the LLM call completes
            currentCoroutineContext().ensureActive()
            
            logger.debug("[{}] Decoding LLM response: {}", agentName, response)
            return LlmCallResult.Success(Json.decodeFromStringWithCodeBlocks<T>(response))
        } catch (e: LlmDeserializationException) {
            lastDeserializationException = e
            val attemptNum = attempt + 1
            if (attemptNum < maxRetries) {
                logger.warn("[{}] LLM deserialization failed on attempt {}/{}: {}. Retrying...", agentName, attemptNum, maxRetries, e.message)
            } else {
                logger.error("[{}] LLM deserialization failed after {} attempts: {}", agentName, maxRetries, e.message)
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
