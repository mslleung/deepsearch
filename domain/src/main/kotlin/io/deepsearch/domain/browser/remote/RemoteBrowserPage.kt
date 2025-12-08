package io.deepsearch.domain.browser.remote

import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.browser.remote.dto.*
import io.deepsearch.domain.constants.ImageMimeType
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Remote browser page that sends commands over WebSocket.
 */
@OptIn(ExperimentalEncodingApi::class)
class RemoteBrowserPage(
    private val sessionId: String,
    private val json: Json,
    private val execute: suspend (PageCommand) -> String?
) : IBrowserPage {

    private val logger = LoggerFactory.getLogger(this::class.java)

    private suspend fun cmd(command: PageCommand): String = execute(command) ?: ""

    private suspend inline fun <reified T> cmdParse(command: PageCommand): T =
        json.decodeFromString<T>(cmd(command))

    override suspend fun navigate(url: String) {
        cmd(PageCommand.Navigate(url))
    }

    override suspend fun getUrl(): String = cmd(PageCommand.GetUrl)
    override suspend fun getFullHtml(): String = cmd(PageCommand.GetFullHtml)
    override suspend fun getTitle(): String = cmd(PageCommand.GetTitle)
    override suspend fun getDescription(): String? = cmd(PageCommand.GetDescription).takeIf { it.isNotEmpty() }
    override suspend fun extractTextContent(): String = cmd(PageCommand.ExtractTextContent)
    override suspend fun extractElementTextContent(elementXPath: String): String =
        cmd(PageCommand.ExtractElementTextContent(elementXPath))

    override suspend fun extractElementTextContentByCssSelector(cssSelector: String): String =
        cmd(PageCommand.ExtractElementTextContentByCssSelector(cssSelector))

    override suspend fun getElementHtmlByXPath(xpath: String): String = cmd(PageCommand.GetElementHtmlByXPath(xpath))
    override suspend fun getElementHtmlByCssSelector(cssSelector: String): String =
        cmd(PageCommand.GetElementHtmlByCssSelector(cssSelector))

    override suspend fun elementExists(xpath: String): Boolean = cmd(PageCommand.ElementExists(xpath)).toBoolean()
    override suspend fun elementExistsByCssSelector(cssSelector: String): Boolean =
        cmd(PageCommand.ElementExistsByCssSelector(cssSelector)).toBoolean()

    override suspend fun isElementVisibleByXPath(xpath: String): Boolean =
        cmd(PageCommand.IsElementVisibleByXPath(xpath)).toBoolean()

    override suspend fun isElementVisibleByCssSelector(cssSelector: String): Boolean =
        cmd(PageCommand.IsElementVisibleByCssSelector(cssSelector)).toBoolean()

    override suspend fun clickByXPathSelector(xpath: String) {
        cmd(PageCommand.ClickByXPathSelector(xpath))
    }

    override suspend fun removeElement(xpath: String) {
        cmd(PageCommand.RemoveElement(xpath))
    }

    override suspend fun removeElementByCssSelector(cssSelector: String) {
        cmd(PageCommand.RemoveElementByCssSelector(cssSelector))
    }

    override suspend fun removeElementsByCssSelectors(selectors: List<String>) {
        cmd(PageCommand.RemoveElementsByCssSelectors(selectors))
    }

    override suspend fun injectAttributeByCssSelector(
        cssSelector: String,
        attributeName: String,
        attributeValue: String
    ) {
        cmd(PageCommand.InjectAttributeByCssSelector(cssSelector, attributeName, attributeValue))
    }

    override suspend fun replaceElementsByXPathWithText(replacements: List<IBrowserPage.XPathReplacementWithText>) {
        cmd(PageCommand.ReplaceElementsByXPathWithText(replacements.map { XPathReplacement(it.xpath, it.text) }))
    }

    override suspend fun replaceElementsByCssSelectorWithText(replacements: List<IBrowserPage.CssSelectorReplacementWithText>) {
        cmd(PageCommand.ReplaceElementsByCssSelectorWithText(replacements.map {
            CssSelectorReplacement(
                it.cssSelector,
                it.text
            )
        }))
    }

    override suspend fun takeScreenshot(): IBrowserPage.Screenshot {
        val r = cmdParse<ScreenshotResponse>(PageCommand.TakeScreenshot)
        return IBrowserPage.Screenshot(Base64.decode(r.base64), ImageMimeType.fromValue(r.mimeType))
    }

    override suspend fun takeFullPageScreenshot(): IBrowserPage.Screenshot {
        val r = cmdParse<ScreenshotResponse>(PageCommand.TakeFullPageScreenshot)
        return IBrowserPage.Screenshot(Base64.decode(r.base64), ImageMimeType.fromValue(r.mimeType))
    }

    override suspend fun getElementScreenshotByXPath(xpath: String): IBrowserPage.Screenshot {
        val r = cmdParse<ScreenshotResponse>(PageCommand.GetElementScreenshotByXPath(xpath))
        return IBrowserPage.Screenshot(Base64.decode(r.base64), ImageMimeType.fromValue(r.mimeType))
    }

    override suspend fun getElementScreenshotByCssSelector(cssSelector: String): IBrowserPage.Screenshot {
        val r = cmdParse<ScreenshotResponse>(PageCommand.GetElementScreenshotByCssSelector(cssSelector))
        return IBrowserPage.Screenshot(Base64.decode(r.base64), ImageMimeType.fromValue(r.mimeType))
    }

    override suspend fun extractMedia(): IBrowserPage.MediaExtractionResult {
        val r = cmdParse<MediaResponse>(PageCommand.ExtractMedia)
        return toMediaResult(r)
    }

    override suspend fun extractIcons(): List<IBrowserPage.Icon> = extractMedia().icons
    override suspend fun extractImages(): List<IBrowserPage.WebImage> = extractMedia().images

    override suspend fun captureSnapshot(): IBrowserPage.PageSnapshot {
        val r = cmdParse<PageSnapshotResponse>(PageCommand.CaptureSnapshot)
        return IBrowserPage.PageSnapshot(
            html = r.html,
            boundingBoxes = r.boundingBoxes.mapValues { (_, b) ->
                IBrowserPage.BoundingBox(b.left, b.top, b.right, b.bottom)
            },
            mediaExtractionResult = toMediaResult(r.media)
        )
    }

    override suspend fun getBoundingBoxesByCssSelector(cssSelector: String): Map<String, IBrowserPage.BoundingBox> {
        val r = cmdParse<Map<String, BoundingBoxResponse>>(PageCommand.GetBoundingBoxesByCssSelector(cssSelector))
        return r.mapValues { (_, b) -> IBrowserPage.BoundingBox(b.left, b.top, b.right, b.bottom) }
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
