package io.deepsearch.domain.browser.remote

import io.deepsearch.domain.browser.ElementOperationException
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.browser.PageOperationException
import io.deepsearch.domain.browser.remote.dto.*
import io.deepsearch.domain.constants.ImageMimeType
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Remote browser page that sends commands over WebSocket.
 * 
 * Commands are categorized into:
 * - Page operations: Affect entire page state, throw PageOperationException on failure
 * - Element operations: Target specific elements, throw ElementOperationException on failure
 * - Idempotent operations: No error expected (remove, replace, inject)
 */
@OptIn(ExperimentalEncodingApi::class)
class RemoteBrowserPage(
    private val sessionId: String,
    private val json: Json,
    private val execute: suspend (PageCommand) -> String?
) : IBrowserPage {

    private val logger = LoggerFactory.getLogger(this::class.java)

    // ==================== Command Execution Helpers ====================

    /**
     * Execute a page-level command. Failures throw PageOperationException.
     */
    private suspend fun pageCmd(command: PageCommand): String {
        try {
            return execute(command) ?: ""
        } catch (e: RemoteBrowserException) {
            throw PageOperationException(e.code, e.message ?: "Page operation failed", e)
        }
    }

    /**
     * Execute a page-level command and parse the result. Failures throw PageOperationException.
     */
    private suspend inline fun <reified T> pageCmdParse(command: PageCommand): T {
        try {
            val result = execute(command) ?: ""
            return json.decodeFromString<T>(result)
        } catch (e: RemoteBrowserException) {
            throw PageOperationException(e.code, e.message ?: "Page operation failed", e)
        }
    }

    /**
     * Execute an element-level command. Failures throw ElementOperationException.
     */
    private suspend fun elementCmd(command: PageCommand): String {
        try {
            return execute(command) ?: ""
        } catch (e: RemoteBrowserException) {
            throw ElementOperationException(e.code, e.message ?: "Element operation failed", e)
        }
    }

    /**
     * Execute an element-level command and parse the result. Failures throw ElementOperationException.
     */
    private suspend inline fun <reified T> elementCmdParse(command: PageCommand): T {
        try {
            val result = execute(command) ?: ""
            return json.decodeFromString<T>(result)
        } catch (e: RemoteBrowserException) {
            throw ElementOperationException(e.code, e.message ?: "Element operation failed", e)
        }
    }

    /**
     * Execute an idempotent command. No error is expected - errors are logged but not thrown.
     */
    private suspend fun idempotentCmd(command: PageCommand) {
        try {
            execute(command)
        } catch (e: RemoteBrowserException) {
            logger.debug("Idempotent command {} failed (ignored): {}", command::class.simpleName, e.message)
        }
    }

    // ==================== Page Operations (throw PageOperationException) ====================

    override suspend fun navigate(url: String) {
        pageCmd(PageCommand.Navigate(url))
    }

    override suspend fun getUrl(): String = pageCmd(PageCommand.GetUrl)
    override suspend fun getFullHtml(): String = pageCmd(PageCommand.GetFullHtml)
    override suspend fun getTitle(): String = pageCmd(PageCommand.GetTitle)
    override suspend fun getDescription(): String? = pageCmd(PageCommand.GetDescription).takeIf { it.isNotEmpty() }
    override suspend fun extractTextContent(): String = pageCmd(PageCommand.ExtractTextContent)

    override suspend fun takeScreenshot(): IBrowserPage.Screenshot {
        val r = pageCmdParse<ScreenshotResponse>(PageCommand.TakeScreenshot)
        return IBrowserPage.Screenshot(Base64.decode(r.base64), ImageMimeType.fromValue(r.mimeType))
    }

    override suspend fun takeFullPageScreenshot(): IBrowserPage.Screenshot {
        val r = pageCmdParse<ScreenshotResponse>(PageCommand.TakeFullPageScreenshot)
        return IBrowserPage.Screenshot(Base64.decode(r.base64), ImageMimeType.fromValue(r.mimeType))
    }

    override suspend fun extractMedia(): IBrowserPage.MediaExtractionResult {
        val r = pageCmdParse<MediaResponse>(PageCommand.ExtractMedia)
        return toMediaResult(r)
    }

    override suspend fun extractIcons(): List<IBrowserPage.Icon> = extractMedia().icons
    override suspend fun extractImages(): List<IBrowserPage.WebImage> = extractMedia().images

    override suspend fun captureSnapshot(): IBrowserPage.PageSnapshot {
        val r = pageCmdParse<PageSnapshotResponse>(PageCommand.CaptureSnapshot)
        return IBrowserPage.PageSnapshot(
            html = r.html,
            boundingBoxes = r.boundingBoxes.mapValues { (_, b) ->
                IBrowserPage.BoundingBox(b.left, b.top, b.right, b.bottom)
            },
            mediaExtractionResult = toMediaResult(r.media)
        )
    }

    override suspend fun getBoundingBoxesByCssSelector(cssSelector: String): Map<String, IBrowserPage.BoundingBox> {
        val r = pageCmdParse<Map<String, BoundingBoxResponse>>(PageCommand.GetBoundingBoxesByCssSelector(cssSelector))
        return r.mapValues { (_, b) -> IBrowserPage.BoundingBox(b.left, b.top, b.right, b.bottom) }
    }

    // ==================== Element Operations (throw ElementOperationException) ====================

    override suspend fun extractElementTextContent(elementXPath: String): String =
        elementCmd(PageCommand.ExtractElementTextContent(elementXPath))

    override suspend fun extractElementTextContentByCssSelector(cssSelector: String): String =
        elementCmd(PageCommand.ExtractElementTextContentByCssSelector(cssSelector))

    override suspend fun getElementHtmlByXPath(xpath: String): String = 
        elementCmd(PageCommand.GetElementHtmlByXPath(xpath))
    
    override suspend fun getElementHtmlByCssSelector(cssSelector: String): String =
        elementCmd(PageCommand.GetElementHtmlByCssSelector(cssSelector))

    override suspend fun getElementScreenshotByXPath(xpath: String): IBrowserPage.Screenshot {
        val r = elementCmdParse<ScreenshotResponse>(PageCommand.GetElementScreenshotByXPath(xpath))
        return IBrowserPage.Screenshot(Base64.decode(r.base64), ImageMimeType.fromValue(r.mimeType))
    }

    override suspend fun getElementScreenshotByCssSelector(cssSelector: String): IBrowserPage.Screenshot {
        val r = elementCmdParse<ScreenshotResponse>(PageCommand.GetElementScreenshotByCssSelector(cssSelector))
        return IBrowserPage.Screenshot(Base64.decode(r.base64), ImageMimeType.fromValue(r.mimeType))
    }

    override suspend fun clickByXPathSelector(xpath: String) {
        elementCmd(PageCommand.ClickByXPathSelector(xpath))
    }

    // ==================== Query Operations (return boolean, throw PageOperationException) ====================

    override suspend fun elementExists(xpath: String): Boolean = 
        pageCmd(PageCommand.ElementExists(xpath)).toBoolean()
    
    override suspend fun elementExistsByCssSelector(cssSelector: String): Boolean =
        pageCmd(PageCommand.ElementExistsByCssSelector(cssSelector)).toBoolean()

    override suspend fun isElementVisibleByXPath(xpath: String): Boolean =
        pageCmd(PageCommand.IsElementVisibleByXPath(xpath)).toBoolean()

    override suspend fun isElementVisibleByCssSelector(cssSelector: String): Boolean =
        pageCmd(PageCommand.IsElementVisibleByCssSelector(cssSelector)).toBoolean()

    // ==================== Idempotent Operations (errors logged but not thrown) ====================

    override suspend fun removeElement(xpath: String) {
        idempotentCmd(PageCommand.RemoveElement(xpath))
    }

    override suspend fun removeElementByCssSelector(cssSelector: String) {
        idempotentCmd(PageCommand.RemoveElementByCssSelector(cssSelector))
    }

    override suspend fun removeElementsByCssSelectors(selectors: List<String>) {
        idempotentCmd(PageCommand.RemoveElementsByCssSelectors(selectors))
    }

    override suspend fun injectAttributeByCssSelector(
        cssSelector: String,
        attributeName: String,
        attributeValue: String
    ) {
        idempotentCmd(PageCommand.InjectAttributeByCssSelector(cssSelector, attributeName, attributeValue))
    }

    override suspend fun replaceElementsByXPathWithText(replacements: List<IBrowserPage.XPathReplacementWithText>) {
        idempotentCmd(PageCommand.ReplaceElementsByXPathWithText(replacements.map { XPathReplacement(it.xpath, it.text) }))
    }

    override suspend fun replaceElementsByCssSelectorWithText(replacements: List<IBrowserPage.CssSelectorReplacementWithText>) {
        idempotentCmd(PageCommand.ReplaceElementsByCssSelectorWithText(replacements.map {
            CssSelectorReplacement(
                it.cssSelector,
                it.text
            )
        }))
    }

    override suspend fun close() {
        logger.debug("RemoteBrowserPage.close() - session release handled by pool")
    }

    private fun toMediaResult(r: MediaResponse) = IBrowserPage.MediaExtractionResult(
        icons = r.icons.map {
            IBrowserPage.Icon(
                Base64.decode(it.base64),
                ImageMimeType.fromValue(it.mimeType),
                it.cssSelectors
            )
        },
        images = r.images.map {
            IBrowserPage.WebImage(
                Base64.decode(it.base64),
                ImageMimeType.fromValue(it.mimeType),
                it.cssSelectors
            )
        },
        failedImages = r.failedImages.map { IBrowserPage.FailedImageInfo(it.cssSelector, it.reason) }
    )
}
