package io.deepsearch.domain.browser

import io.deepsearch.domain.exceptions.UrlProcessingException

/**
 * Base exception for browser operations.
 * Extends UrlProcessingException so browser failures are handled gracefully
 * by URL processing pipelines (e.g., PeriodicIndexJobRegistry).
 * 
 * Contains the error code from the browser service for debugging/logging.
 */
sealed class BrowserOperationException(
    url: String,
    val code: String,
    message: String,
    cause: Throwable? = null
) : UrlProcessingException(url, message, cause)

/**
 * Exception thrown when an element-level operation fails.
 * 
 * Element operations target specific DOM elements and can fail due to:
 * - Element not found/visible/stable
 * - Timeout waiting for element
 * - Element detached from DOM
 * 
 * These failures are RECOVERABLE - the caller can catch and skip the element,
 * continuing with other operations.
 * 
 * Applies to: getElementScreenshot*, getElementHtml*, extractElementTextContent*, click*
 */
class ElementOperationException(
    url: String,
    code: String,
    message: String,
    cause: Throwable? = null
) : BrowserOperationException(url, code, message, cause)

/**
 * Exception thrown when a page-level operation fails.
 * 
 * Page operations affect the entire page state and typically indicate
 * more serious issues:
 * - Navigation failures (DNS, SSL, HTTP errors)
 * - Page screenshot failures
 * - Media extraction failures
 * 
 * These failures are typically FATAL for the current URL - the caller
 * should record the failure and continue with other URLs.
 * 
 * Applies to: navigate, takeScreenshot*, captureSnapshot, extractMedia, getFullHtml, etc.
 */
class PageOperationException(
    url: String,
    code: String,
    message: String,
    cause: Throwable? = null
) : BrowserOperationException(url, code, message, cause)

