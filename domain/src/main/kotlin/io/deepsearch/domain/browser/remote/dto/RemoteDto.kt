package io.deepsearch.domain.browser.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ==================== Page Commands ====================

@Serializable
sealed class PageCommand {
    @Serializable @SerialName("navigate")
    data class Navigate(val url: String) : PageCommand()
    
    @Serializable @SerialName("waitForLoad")
    data object WaitForLoad : PageCommand()
    
    @Serializable @SerialName("navigateWithCachedHtml")
    data class NavigateWithCachedHtml(val url: String, val htmlBodyBase64: String) : PageCommand()
    
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
    
    @Serializable @SerialName("clickByCssSelector")
    data class ClickByCssSelector(val cssSelector: String) : PageCommand()
    
    @Serializable @SerialName("scrollToElementByCssSelector")
    data class ScrollToElementByCssSelector(val cssSelector: String) : PageCommand()

    @Serializable @SerialName("scrollToTextContent")
    data class ScrollToTextContent(val searchText: String, val occurrence: Int = 1) : PageCommand()

    @Serializable @SerialName("scrollToTextInDirection")
    data class ScrollToTextInDirection(val searchText: String, val direction: String) : PageCommand()

    @Serializable @SerialName("countTextMatches")
    data class CountTextMatches(val keywords: List<String>) : PageCommand()

    @Serializable @SerialName("scrollToPercentage")
    data class ScrollToPercentage(val percent: Int) : PageCommand()

    @Serializable @SerialName("getScrollPosition")
    data object GetScrollPosition : PageCommand()
    
    @Serializable @SerialName("scrollPage")
    data class ScrollPage(val deltaX: Int = 0, val deltaY: Int = 0) : PageCommand()

    @Serializable @SerialName("scrollElementAtCoordinates")
    data class ScrollElementAtCoordinates(val x: Int, val y: Int, val deltaX: Int, val deltaY: Int) : PageCommand()
    
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
    
    @Serializable @SerialName("extractIcons")
    data object ExtractIcons : PageCommand()
    
    @Serializable @SerialName("extractImages")
    data object ExtractImages : PageCommand()
    
    @Serializable @SerialName("extractImagesWithScreenshot")
    data class ExtractImagesWithScreenshot(val screenshotBase64: String, val mimeType: String) : PageCommand()
    
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
    
    // ==================== Optimized Composite Commands ====================
    
    @Serializable @SerialName("captureFullSnapshot")
    data object CaptureFullSnapshot : PageCommand()
    
    @Serializable @SerialName("capturePageSnapshot")
    data object CapturePageSnapshot : PageCommand()
    
    @Serializable @SerialName("injectAttributesByCssSelectors")
    data class InjectAttributesByCssSelectors(val injections: List<AttributeInjection>) : PageCommand()
    
    @Serializable @SerialName("injectStableIds")
    data object InjectStableIds : PageCommand()
    
    @Serializable @SerialName("getTableInterpretationData")
    data class GetTableInterpretationData(val cssSelector: String) : PageCommand()
    
    @Serializable @SerialName("extractElementsTextContentByCssSelectors")
    data class ExtractElementsTextContentByCssSelectors(val selectors: List<String>) : PageCommand()
    
    @Serializable @SerialName("elementsExistByCssSelectors")
    data class ElementsExistByCssSelectors(val selectors: List<String>) : PageCommand()
    
    @Serializable @SerialName("getElementsHtmlByCssSelectors")
    data class GetElementsHtmlByCssSelectors(val selectors: List<String>) : PageCommand()
    
    @Serializable @SerialName("getTablesInterpretationData")
    data class GetTablesInterpretationData(val selectors: List<String>) : PageCommand()

    @Serializable @SerialName("captureHiddenContainerBoundingBoxes")
    data object CaptureHiddenContainerBoundingBoxes : PageCommand()

    @Serializable @SerialName("clickAtCoordinates")
    data class ClickAtCoordinates(val x: Int, val y: Int) : PageCommand()

    @Serializable @SerialName("guardedClickAtCoordinates")
    data class GuardedClickAtCoordinates(val x: Int, val y: Int) : PageCommand()

    @Serializable @SerialName("typeText")
    data class TypeText(val text: String) : PageCommand()

    @Serializable @SerialName("getElementsAtPoints")
    data class GetElementsAtPoints(val points: List<PointCoord>) : PageCommand()

    @Serializable @SerialName("getInteractiveElements")
    data object GetInteractiveElements : PageCommand()
}

@Serializable
data class PointCoord(val x: Int, val y: Int)

@Serializable
data class XPathReplacement(val xpath: String, val text: String? = null)

@Serializable
data class CssSelectorReplacement(val cssSelector: String, val text: String? = null)

@Serializable
data class AttributeInjection(
    val cssSelector: String,
    val attributeName: String,
    val attributeValue: String
)

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
data class IconsResponse(
    val icons: List<IconResponse>
)

@Serializable
data class ImagesResponse(
    val images: List<ImageResponse>
)

@Serializable
data class BoundingBoxResponse(val left: Double, val top: Double, val right: Double, val bottom: Double)

@Serializable
data class PageSnapshotResponse(
    val html: String,
    val boundingBoxes: Map<String, BoundingBoxResponse>,
    val media: MediaResponse
)

@Serializable
data class FullPageSnapshotResponse(
    val title: String,
    val description: String?,
    val url: String,
    val html: String,
    val boundingBoxes: Map<String, BoundingBoxResponse>,
    val media: MediaResponse
)

@Serializable
data class StableIdInjectionResponse(
    /** Number of structural/semantic elements injected (ds-element-N) */
    val elements: Int,
    /** Number of icon elements injected (ds-icon-N) */
    val icons: Int,
    /** Number of image elements injected (ds-image-N) */
    val images: Int
)

@Serializable
data class PageSnapshotWithMetadataResponse(
    val title: String,
    val description: String?,
    val url: String,
    val html: String,
    val boundingBoxes: Map<String, BoundingBoxResponse>
)

@Serializable
data class TableInterpretationDataResponse(
    val html: String,
    val boundingBoxes: Map<String, BoundingBoxResponse>
)

@Serializable
data class TablesInterpretationDataResponse(
    val data: Map<String, TableInterpretationDataResponse>
)

// ==================== Guarded Click Response ====================

@Serializable
data class GuardedClickResponse(val navigatedAwayTo: String?)

// ==================== Element At Point Response ====================

@Serializable
data class ElementAtPointResponse(val path: String, val tag: String, val text: String)

@Serializable
data class ElementsAtPointsResponse(val elements: List<ElementAtPointResponse?>)

@Serializable
data class InteractiveElementDto(
    val tag: String,
    val text: String,
    val role: String? = null,
    val ariaLabel: String? = null,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val index: Int
)

@Serializable
data class InteractiveElementsResponse(val elements: List<InteractiveElementDto>)

// ==================== Hidden Container Bounding Boxes Response ====================

@Serializable
data class HiddenContainerBoundingBoxesResponse(
    /** Data for each hidden container */
    val hiddenContainers: List<HiddenContainerBoundingBoxDataResponse>,
    /** Number of hidden containers found */
    val hiddenContainerCount: Int,
    /** Total elements captured across all containers */
    val totalElementsCaptured: Int,
    /** Icons extracted from hidden containers (now visible after reveal) */
    val hiddenIcons: List<HiddenIconResponse> = emptyList(),
    /** Images extracted from hidden containers (now visible after reveal) */
    val hiddenImages: List<HiddenImageResponse> = emptyList(),
    /** Browser-side debug log entries */
    val debugLog: List<String>? = null
)

@Serializable
data class HiddenContainerBoundingBoxDataResponse(
    /** The data-ds-id attribute value of the container element (always a stable injected ID, never a CSS path) */
    val containerDataId: String,
    /** Container outerHTML with data-ds-local attributes (for server-side parsing) */
    val containerHtml: String,
    /** Bounding box of the container itself */
    val containerBox: BoundingBoxResponse,
    /** Map of local element ID (ds-local-N) to bounding box */
    val elements: Map<String, BoundingBoxResponse>,
    /** Type of container: 'leaf', 'residual', or 'merged' */
    val containerType: String = "leaf",
    /** For 'residual'/'merged' containers: data-ds-id values of nested leaf children */
    val nestedChildIds: List<String> = emptyList()
)

@Serializable
data class HiddenIconResponse(
    /** Base64-encoded PNG of the icon */
    val base64: String,
    /** CSS selector using data-ds-id (same as main icon extraction) */
    val cssSelector: String
)

@Serializable
data class HiddenImageResponse(
    /** Base64-encoded image data */
    val base64: String,
    /** MIME type of the image */
    val mimeType: String,
    /** CSS selector using data-ds-id (same as main image extraction) */
    val cssSelector: String
)
