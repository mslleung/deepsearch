package io.deepsearch.domain.exceptions

import io.deepsearch.domain.models.valueobjects.UrlFailureReason

/**
 * Exception thrown when URL processing fails.
 * Includes the categorized failure reason for tracking and analytics.
 * 
 * This exception is thrown by URL processing flows and should be caught
 * using Flow's .catch{} operator for proper error handling.
 */
class UrlProcessingException(
    val url: String,
    val reason: UrlFailureReason,
    message: String,
    cause: Throwable? = null
) : Exception("Failed to process $url: $message (reason: $reason)", cause)


