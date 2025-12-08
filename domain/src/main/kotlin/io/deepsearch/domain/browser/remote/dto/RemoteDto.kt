package io.deepsearch.domain.browser.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ==================== Client → Server ====================

@Serializable
sealed class ClientMessage {
    abstract val requestId: String
    
    @Serializable
    @SerialName("acquire")
    data class Acquire(override val requestId: String) : ClientMessage()
    
    @Serializable
    @SerialName("release")
    data class Release(override val requestId: String, val sessionId: String) : ClientMessage()
    
    @Serializable
    @SerialName("command")
    data class Command(
        override val requestId: String,
        val sessionId: String,
        val command: PageCommand
    ) : ClientMessage()
}

// ==================== Server → Client ====================

@Serializable
sealed class ServerMessage {
    abstract val requestId: String
    
    @Serializable
    @SerialName("acquired")
    data class Acquired(
        override val requestId: String,
        val sessionId: String
    ) : ServerMessage()
    
    @Serializable
    @SerialName("released")
    data class Released(override val requestId: String) : ServerMessage()
    
    @Serializable
    @SerialName("result")
    data class Result(
        override val requestId: String,
        val success: Boolean,
        val data: String? = null
    ) : ServerMessage()
    
    @Serializable
    @SerialName("error")
    data class Error(
        override val requestId: String,
        val code: String,
        val message: String
    ) : ServerMessage()
}

// ==================== Page Commands ====================

@Serializable
sealed class PageCommand {
    @Serializable @SerialName("navigate")
    data class Navigate(val url: String) : PageCommand()
    
    @Serializable @SerialName("getUrl")
    data object GetUrl : PageCommand()
    
    @Serializable @SerialName("takeScreenshot")
    data object TakeScreenshot : PageCommand()
    
    @Serializable @SerialName("takeFullPageScreenshot")
    data object TakeFullPageScreenshot : PageCommand()
    
    @Serializable @SerialName("getFullHtml")
    data object GetFullHtml : PageCommand()
    
    @Serializable @SerialName("getTitle")
    data object GetTitle : PageCommand()
    
    @Serializable @SerialName("getDescription")
    data object GetDescription : PageCommand()
    
    @Serializable @SerialName("getElementScreenshotByXPath")
    data class GetElementScreenshotByXPath(val xpath: String) : PageCommand()
    
    @Serializable @SerialName("getElementScreenshotByCssSelector")
    data class GetElementScreenshotByCssSelector(val cssSelector: String) : PageCommand()
    
    @Serializable @SerialName("getElementHtmlByXPath")
    data class GetElementHtmlByXPath(val xpath: String) : PageCommand()
    
    @Serializable @SerialName("getElementHtmlByCssSelector")
    data class GetElementHtmlByCssSelector(val cssSelector: String) : PageCommand()
    
    @Serializable @SerialName("clickByXPathSelector")
    data class ClickByXPathSelector(val xpath: String) : PageCommand()
    
    @Serializable @SerialName("removeElement")
    data class RemoveElement(val xpath: String) : PageCommand()
    
    @Serializable @SerialName("removeElementByCssSelector")
    data class RemoveElementByCssSelector(val cssSelector: String) : PageCommand()
    
    @Serializable @SerialName("removeElementsByCssSelectors")
    data class RemoveElementsByCssSelectors(val selectors: List<String>) : PageCommand()
    
    @Serializable @SerialName("elementExists")
    data class ElementExists(val xpath: String) : PageCommand()
    
    @Serializable @SerialName("elementExistsByCssSelector")
    data class ElementExistsByCssSelector(val cssSelector: String) : PageCommand()
    
    @Serializable @SerialName("isElementVisibleByCssSelector")
    data class IsElementVisibleByCssSelector(val cssSelector: String) : PageCommand()
    
    @Serializable @SerialName("isElementVisibleByXPath")
    data class IsElementVisibleByXPath(val xpath: String) : PageCommand()
    
    @Serializable @SerialName("extractMedia")
    data object ExtractMedia : PageCommand()
    
    @Serializable @SerialName("captureSnapshot")
    data object CaptureSnapshot : PageCommand()
    
    @Serializable @SerialName("replaceElementsByXPathWithText")
    data class ReplaceElementsByXPathWithText(val replacements: List<XPathReplacement>) : PageCommand()
    
    @Serializable @SerialName("replaceElementsByCssSelectorWithText")
    data class ReplaceElementsByCssSelectorWithText(val replacements: List<CssSelectorReplacement>) : PageCommand()
    
    @Serializable @SerialName("extractTextContent")
    data object ExtractTextContent : PageCommand()
    
    @Serializable @SerialName("extractElementTextContent")
    data class ExtractElementTextContent(val xpath: String) : PageCommand()
    
    @Serializable @SerialName("extractElementTextContentByCssSelector")
    data class ExtractElementTextContentByCssSelector(val cssSelector: String) : PageCommand()
    
    @Serializable @SerialName("getBoundingBoxesByCssSelector")
    data class GetBoundingBoxesByCssSelector(val cssSelector: String) : PageCommand()
    
    @Serializable @SerialName("injectAttributeByCssSelector")
    data class InjectAttributeByCssSelector(
        val cssSelector: String,
        val attributeName: String,
        val attributeValue: String
    ) : PageCommand()
}

@Serializable
data class XPathReplacement(val xpath: String, val text: String? = null)

@Serializable
data class CssSelectorReplacement(val cssSelector: String, val text: String? = null)

// ==================== Response Data Types ====================

@Serializable
data class ScreenshotResponse(val base64: String, val mimeType: String)

@Serializable
data class IconResponse(val base64: String, val mimeType: String, val cssSelectors: List<String>)

@Serializable
data class ImageResponse(val base64: String, val mimeType: String, val cssSelectors: List<String>)

@Serializable
data class FailedImageResponse(val cssSelector: String, val reason: String)

@Serializable
data class MediaResponse(
    val icons: List<IconResponse>,
    val images: List<ImageResponse>,
    val failedImages: List<FailedImageResponse>
)

@Serializable
data class BoundingBoxResponse(val left: Double, val top: Double, val right: Double, val bottom: Double)

@Serializable
data class PageSnapshotResponse(
    val html: String,
    val boundingBoxes: Map<String, BoundingBoxResponse>,
    val media: MediaResponse
)
