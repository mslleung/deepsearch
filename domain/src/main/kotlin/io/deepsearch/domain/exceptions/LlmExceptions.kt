package io.deepsearch.domain.exceptions

/**
 * Base exception for all LLM-related errors.
 * Provides type-safe exception handling for LLM operations.
 */
sealed class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * LLM request exceeded the timeout period.
 * This can happen due to slow LLM response or network issues.
 */
class LlmTimeoutException(cause: Throwable) : LlmException("LLM request timed out", cause)

/**
 * LLM rate limit was exceeded (HTTP 429).
 * May require exponential backoff or switching to a different model.
 */
class LlmRateLimitException(cause: Throwable) : LlmException("LLM rate limit exceeded", cause)

/**
 * Failed to deserialize LLM response as expected JSON structure.
 * This indicates the LLM returned malformed or unexpected output.
 */
class LlmDeserializationException(message: String, cause: Throwable) : LlmException(message, cause)

/**
 * Generic LLM error for unexpected failures.
 * Used as a catch-all for ADK/LLM errors that don't fit specific categories.
 */
class LlmGenericException(message: String, cause: Throwable? = null) : LlmException(message, cause)
