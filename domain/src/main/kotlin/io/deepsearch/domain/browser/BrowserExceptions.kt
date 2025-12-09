package io.deepsearch.domain.browser

/**
 * Base exception for browser operations.
 * Contains the error code from the browser service for debugging/logging.
 */
sealed class BrowserOperationException(
    val code: String,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

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
    code: String,
    message: String,
    cause: Throwable? = null
) : BrowserOperationException(code, message, cause)

/**
 * Exception thrown when a page-level operation fails.
 * 
 * Page operations affect the entire page state and typically indicate
 * more serious issues:
 * - Navigation failures (DNS, SSL, HTTP errors)
 * - Page screenshot failures
 * - Media extraction failures
 * 
 * These failures are typically FATAL for the current operation - the caller
 * should propagate them rather than trying to continue.
 * 
 * Applies to: navigate, takeScreenshot*, captureSnapshot, extractMedia, getFullHtml, etc.
 */
class PageOperationException(
    code: String,
    message: String,
    cause: Throwable? = null
) : BrowserOperationException(code, message, cause)

