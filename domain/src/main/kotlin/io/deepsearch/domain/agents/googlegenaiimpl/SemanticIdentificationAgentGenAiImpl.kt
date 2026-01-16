package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.agents.ISemanticIdentificationAgent
import io.deepsearch.domain.agents.SemanticIdentificationBatchRequest
import io.deepsearch.domain.agents.SemanticIdentificationInput
import io.deepsearch.domain.agents.SemanticIdentificationOutput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.IdentifiedElement
import io.deepsearch.domain.models.valueobjects.SemanticElements
import io.deepsearch.domain.services.BatchContentRequest
import io.deepsearch.domain.services.ICssSelectorConstructionService
import io.deepsearch.domain.services.IImageDimensionService
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class SemanticIdentificationAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val cssSelectorConstructionService: ICssSelectorConstructionService,
    private val imageDimensionService: IImageDimensionService,
    private val dispatcherProvider: IDispatcherProvider
) : ISemanticIdentificationAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val identifiedElementSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("An identified element with stable ID and note")
        .properties(
            mapOf(
                "id" to Schema.builder().type("STRING")
                    .description("The data-ds-id value of the semantic element (e.g., 'ds-semantic-0', 'ds-semantic-15')")
                    .build(),
                "note" to Schema.builder().type("STRING").description("Brief note of what this element is").build()
            )
        )
        .build()

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Structured collection of all semantic elements on the webpage")
        .properties(
            mapOf(
                "header" to Schema.builder().type("OBJECT").properties(
                    identifiedElementSchema.properties().orElse(emptyMap())
                ).description("Navigation bar at the top of the page (optional)").nullable(true).build(),
                "footer" to Schema.builder().type("OBJECT").properties(
                    identifiedElementSchema.properties().orElse(emptyMap())
                ).description("Footer at the bottom of the page (optional)").nullable(true).build(),
                "navSidebar" to Schema.builder().type("OBJECT").properties(
                    identifiedElementSchema.properties().orElse(emptyMap())
                ).description("Navigation sidebar (optional)").nullable(true).build(),
                "breadcrumb" to Schema.builder().type("OBJECT").properties(
                    identifiedElementSchema.properties().orElse(emptyMap())
                ).description("Breadcrumb navigation bar (optional)").nullable(true).build(),
                "cookieBanner" to Schema.builder().type("OBJECT").properties(
                    identifiedElementSchema.properties().orElse(emptyMap())
                ).description("Cookie consent banner (optional)").nullable(true).build(),
                "adBanners" to Schema.builder().type("ARRAY").items(identifiedElementSchema)
                    .description("List of advertisement banners").build(),
                "popups" to Schema.builder().type("ARRAY").items(identifiedElementSchema)
                    .description("List of popups and modal dialogs").build()
            )
        )
        .build()

    // ========== Vision-Based Detection Schema ==========
    // Uses official Gemini object detection format: box_2d = [ymin, xmin, ymax, xmax] scaled to [0, 1000]
    // See: https://ai.google.dev/gemini-api/docs/image-understanding#object-detection

    private val visionElementSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "box_2d" to Schema.builder().type("ARRAY")
                    .description("Bounding box as [ymin, xmin, ymax, xmax] scaled to [0, 1000]")
                    .items(Schema.builder().type("INTEGER").build())
                    .build(),
                "label" to Schema.builder().type("STRING")
                    .description("Brief description of the element")
                    .build()
            )
        )
        .required(listOf("box_2d", "label"))
        .build()

    private val visionOutputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Semantic elements identified visually with bounding boxes")
        .properties(
            mapOf(
                "header" to visionElementSchema.toBuilder().nullable(true).build(),
                "footer" to visionElementSchema.toBuilder().nullable(true).build(),
                "navSidebar" to visionElementSchema.toBuilder().nullable(true).build(),
                "breadcrumb" to visionElementSchema.toBuilder().nullable(true).build(),
                "cookieBanner" to visionElementSchema.toBuilder().nullable(true).build(),
                "popups" to Schema.builder().type("ARRAY")
                    .items(visionElementSchema)
                    .build()
            )
        )
        .build()

    private val visionSystemInstruction = """
        Detect semantic/navigational elements in this webpage screenshot.
        
        Return bounding boxes using box_2d format: [ymin, xmin, ymax, xmax] where coordinates are scaled to [0, 1000].
        - ymin/ymax: vertical position (0 = top, 1000 = bottom)
        - xmin/xmax: horizontal position (0 = left, 1000 = right)
        
        Detect these elements if any:
        - header: webpage header/navigation bar 
        - footer: webpage footer
        - navSidebar: Side navigation column
        - breadcrumb: Path like "Home > Category"
        - cookieBanner: Cookie consent popup
        - popups: Popup modals/dialogs
        
        Example: 
        {
            "header": {"box_2d": [0, 0, 50, 1000], "label": "Navigation bar"},
            "footer": {"box_2d": [920, 0, 1000, 1000], "label": "Footer with copyright"}
        }     
    """.trimIndent()

    /**
     * Vision detection result using official Gemini format.
     * box_2d format: [ymin, xmin, ymax, xmax] scaled to [0, 1000]
     */
    @Serializable
    private data class VisionSemanticElement(
        @kotlinx.serialization.SerialName("box_2d")
        val box2d: List<Int>,
        val label: String
    ) {
        // Helper properties to extract coordinates
        val ymin: Int get() = box2d.getOrElse(0) { 0 }
        val xmin: Int get() = box2d.getOrElse(1) { 0 }
        val ymax: Int get() = box2d.getOrElse(2) { 1000 }
        val xmax: Int get() = box2d.getOrElse(3) { 1000 }
    }

    @Serializable
    private data class VisionSemanticResponse(
        val header: VisionSemanticElement? = null,
        val footer: VisionSemanticElement? = null,
        val navSidebar: VisionSemanticElement? = null,
        val breadcrumb: VisionSemanticElement? = null,
        val cookieBanner: VisionSemanticElement? = null,
        val popups: List<VisionSemanticElement>? = null
    )

    private val systemInstruction = """
        Task: Identify popup and navigational elements on the page and return their stable identifiers and a short note.

        Inputs:
        - CLEANED HTML (subset of DOM with key attributes, all potential semantic elements have data-ds-id attributes)

        Guidelines:
        - Identify the following if any:
            (Max 1 of each)
            - header: The top header bar of the webpage, usually for navigation
            - footer: The bottom footer bar of the webpage
            - navSidebar: The navigational sidebar of the current main content
            - breadcrumb: The navigational breadcrumb
            - cookieBanner: The cookie banner dialog
            (Can be multiple)
            - adBanners: Elements containing ads
            - popups: Popup dialogs that cover the main content and are visually interrupting to the user
        - Do not include the main content of the webpage. Only include elements that contain no critical information in the webpage.
        - For each element, return its data-ds-id attribute value (e.g., "ds-semantic-5").
        - Make sure the regions are unique with no overlap.
        - Write a brief note describing why it is considered a semantic element (e.g., "Top navbar with logo and menu", "Left sidebar with category links", "Cookie consent popup").
        - Return null for optional single elements if not found.
        - Return empty arrays for list elements if none found.

        Output structure:
        {
          "header": { "id": string, "note": string } | null,
          "footer": { "id": string, "note": string } | null,
          "navSidebar": { "id": string, "note": string } | null,
          "breadcrumb": { "id": string, "note": string } | null,
          "cookieBanner": { "id": string, "note": string } | null,
          "adBanners": [ { "id": string, "note": string }, ... ],
          "popups": [ { "id": string, "note": string }, ... ]
        }
    """.trimIndent()

    @Serializable
    private data class LlmSemanticResult(
        val id: String,
        val note: String
    )

    @Serializable
    private data class SemanticIdentificationResponse(
        val header: LlmSemanticResult? = null,
        val footer: LlmSemanticResult? = null,
        val navSidebar: LlmSemanticResult? = null,
        val breadcrumb: LlmSemanticResult? = null,
        val cookieBanner: LlmSemanticResult? = null,
        val adBanners: List<LlmSemanticResult> = emptyList(),
        val popups: List<LlmSemanticResult> = emptyList()
    )

    override suspend fun generate(input: SemanticIdentificationInput): SemanticIdentificationOutput {
        val originalHtml = input.pageSnapshot.html
        val boundingBoxes = input.pageSnapshot.boundingBoxes
        val screenshot = input.screenshot

        logger.debug(
            "Semantic identification: HTML={} bytes, screenshot={} bytes, {} bounding boxes",
            originalHtml.length, screenshot.bytes.size, boundingBoxes.size
        )

        // Inject stable identifiers for DOM element lookup
        val htmlWithIds = injectStableIdentifiers(originalHtml, "ds-semantic")

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        // ========== Scale image for Gemini API if needed ==========
        // Very tall full-page screenshots can cause "Unable to process input image" errors
        val scaledResult = withContext(dispatcherProvider.io) {
            imageDimensionService.scaleImageForGemini(screenshot.bytes, maxDimension = 4096)
        }
        
        if (scaledResult.wasScaled) {
            logger.info(
                "Scaled screenshot from {}x{} to fit within 4096px (scale factor: {:.3f})",
                scaledResult.originalWidth, scaledResult.originalHeight, scaledResult.scaleFactor
            )
        }

        // ========== Vision-based detection ==========
        val visionResponse = withContext(dispatcherProvider.io) {
            retryLlmCall<VisionSemanticResponse>(this@SemanticIdentificationAgentGenAiImpl::class.simpleName!! + "_vision") {
                val result = client.models.generateContent(
                    modelId,
                    listOf(
                        Content.fromParts(
                            // Use scaled image for Gemini API
                            Part.fromBytes(scaledResult.scaledBytes, scaledResult.scaledMimeType),
                            Part.fromText("Analyze this screenshot")
                        )
                    ),
                    GenerateContentConfig.builder()
                        .temperature(0F)
                        .responseSchema(visionOutputSchema)
                        .responseMimeType("application/json")
                        .thinkingConfig(
                            ThinkingConfig.builder()
                                .thinkingBudget(0)
                                .build()
                        )
                        .systemInstruction(Content.fromParts(Part.fromText(visionSystemInstruction)))
                        .build()
                )

                result.checkFinishReason()

                result.usageMetadata().ifPresent { metadata ->
                    tokenUsage = TokenUsageMetrics(
                        modelName = modelId,
                        promptTokens = metadata.promptTokenCount().orElse(0),
                        outputTokens = metadata.candidatesTokenCount().orElse(0),
                        totalTokens = metadata.totalTokenCount().orElse(0)
                    )
                }

                result.text() ?: throw RuntimeException("No text response from model")
            }
        }

        // ========== Map vision bounding boxes to DOM elements ==========
        // Use original dimensions for coordinate mapping (Gemini uses normalized [0, 1000] coords)
        var response = mapVisionToDomElements(
            visionResponse, 
            boundingBoxes, 
            htmlWithIds, 
            scaledResult.originalWidth.toDouble(),
            scaledResult.originalHeight.toDouble()
        )

        // ========== Programmatic fallback for semantic HTML elements ==========
        val doc = Jsoup.parse(htmlWithIds)

        // Fallback for header: look for <header> element
        val semanticHeader = doc.selectFirst("header[data-ds-id]")
        if (semanticHeader != null) {
            val dataId = semanticHeader.attr("data-ds-id")
            logger.debug("Programmatic fallback: Found <header> element with id {}", dataId)
            response = response.copy(header = LlmSemanticResult(id = dataId, note = "Semantic <header> element"))

        }

        // Fallback for footer: look for <footer> element
        val semanticFooter = doc.selectFirst("footer[data-ds-id]")
        if (semanticFooter != null) {
            val dataId = semanticFooter.attr("data-ds-id")
            logger.debug("Programmatic fallback: Found <footer> element with id {}", dataId)
            response = response.copy(footer = LlmSemanticResult(id = dataId, note = "Semantic <footer> element"))
        }

        // Fallback for navSidebar: look for <nav> or <aside> with navigation role
        val semanticNav =
            doc.selectFirst("nav[data-ds-id]:not(header nav):not(footer nav), aside[role=navigation][data-ds-id]")
        if (semanticNav != null) {
            val dataId = semanticNav.attr("data-ds-id")
            logger.debug("Programmatic fallback: Found <nav>/<aside> element with id {}", dataId)
            response =
                response.copy(navSidebar = LlmSemanticResult(id = dataId, note = "Semantic <nav>/<aside> element"))

        }

        // Step 5: Reconstruct CSS selectors for all identified elements using batch method (single HTML parse)
        data class ResolvedElement(
            val llmResult: LlmSemanticResult,
            val cssSelector: String
        )

        // Collect all identifiers for batch processing
        val allLlmResults = listOfNotNull(
            response.header, response.footer, response.navSidebar,
            response.breadcrumb, response.cookieBanner
        ) + response.adBanners + response.popups

        val identifiers = allLlmResults.map { it.id }
        val cssSelectorsMap = cssSelectorConstructionService.constructCssSelectorsFromIdentifiers(
            identifiers = identifiers,
            htmlWithIdentifiers = htmlWithIds
        )

        fun resolveElement(llmResult: LlmSemanticResult?): ResolvedElement? {
            if (llmResult == null) return null

            val cssSelector = cssSelectorsMap[llmResult.id]

            if (cssSelector == null) {
                logger.warn(
                    "Skipping semantic element with ID '{}' - could not construct valid CSS selector",
                    llmResult.id
                )
                return null
            }

            logger.debug("Constructed CSS selector '{}' for identifier '{}'", cssSelector, llmResult.id)
            return ResolvedElement(llmResult, cssSelector)
        }

        // Resolve all elements (no browser calls, uses pre-computed map)
        val resolvedHeader = resolveElement(response.header)
        val resolvedFooter = resolveElement(response.footer)
        val resolvedNavSidebar = resolveElement(response.navSidebar)
        val resolvedBreadcrumb = resolveElement(response.breadcrumb)
        val resolvedCookieBanner = resolveElement(response.cookieBanner)
        val resolvedAdBanners = response.adBanners.mapNotNull { resolveElement(it) }
        val resolvedPopups = response.popups.mapNotNull { resolveElement(it) }

        // Convert resolved elements to IdentifiedElements with both cssSelector and dataId
        // The cssSelector is used for initial injection, dataId for subsequent operations
        fun toIdentifiedElement(resolved: ResolvedElement?): IdentifiedElement? {
            if (resolved == null) return null
            return IdentifiedElement(
                cssSelector = resolved.cssSelector,
                dataId = resolved.llmResult.id,
                note = resolved.llmResult.note
            )
        }

        val semanticElements = SemanticElements(
            header = toIdentifiedElement(resolvedHeader),
            footer = toIdentifiedElement(resolvedFooter),
            navSidebar = toIdentifiedElement(resolvedNavSidebar),
            breadcrumb = toIdentifiedElement(resolvedBreadcrumb),
            cookieBanner = toIdentifiedElement(resolvedCookieBanner),
            adBanners = resolvedAdBanners.map { toIdentifiedElement(it)!! },
            popups = resolvedPopups.map { toIdentifiedElement(it)!! }
        )

        logger.debug(
            "Semantic identification complete: header={}, footer={}, popups={}",
            semanticElements.header != null,
            semanticElements.footer != null,
            semanticElements.popups.size
        )

        return SemanticIdentificationOutput(
            elements = semanticElements,
            tokenUsage = tokenUsage
        )
    }

    /**
     * Inject stable data-ds-id attributes into potential semantic elements.
     */

    /**
     * Map vision-detected bounding boxes to DOM elements using IoU matching.
     * Uses actual screenshot dimensions for accurate coordinate mapping.
     * 
     * @param pageWidth Original screenshot width (before any Gemini scaling)
     * @param pageHeight Original screenshot height (before any Gemini scaling)
     */
    private fun mapVisionToDomElements(
        visionResponse: VisionSemanticResponse,
        pageBoundingBoxes: Map<String, IBrowserPage.BoundingBox>,
        htmlWithIds: String,
        pageWidth: Double,
        pageHeight: Double
    ): SemanticIdentificationResponse {
        if (pageBoundingBoxes.isEmpty()) {
            logger.warn("No bounding boxes available for vision-to-DOM mapping")
            return SemanticIdentificationResponse()
        }

        val doc = Jsoup.parse(htmlWithIds)

        logger.debug("Semantic mapping dimensions: {}x{}", pageWidth.toInt(), pageHeight.toInt())

        fun mapSingleElement(visionElement: VisionSemanticElement?, elementType: String): LlmSemanticResult? {
            if (visionElement == null) return null

            // Convert [0, 1000] scaled coordinates to absolute pixel coordinates
            // box_2d format: [ymin, xmin, ymax, xmax]
            val targetTop = visionElement.ymin * pageHeight / 1000
            val targetLeft = visionElement.xmin * pageWidth / 1000
            val targetBottom = visionElement.ymax * pageHeight / 1000
            val targetRight = visionElement.xmax * pageWidth / 1000
            val targetArea = (targetRight - targetLeft) * (targetBottom - targetTop)

            if (targetArea <= 0) return null

            var bestMatch: Triple<String, Double, Double>? = null // dataId, score, iou

            for ((xpath, bbox) in pageBoundingBoxes) {
                val elemLeft = bbox.left
                val elemTop = bbox.top
                val elemRight = bbox.right
                val elemBottom = bbox.bottom
                val elemArea = (elemRight - elemLeft) * (elemBottom - elemTop)

                if (elemArea <= 0) continue

                // Calculate intersection
                val interLeft = maxOf(targetLeft, elemLeft)
                val interTop = maxOf(targetTop, elemTop)
                val interRight = minOf(targetRight, elemRight)
                val interBottom = minOf(targetBottom, elemBottom)

                if (interLeft < interRight && interTop < interBottom) {
                    val interArea = (interRight - interLeft) * (interBottom - interTop)

                    // IoU: intersection / union
                    val unionArea = targetArea + elemArea - interArea
                    val iou = if (unionArea > 0) interArea / unionArea else 0.0

                    // Vision coverage: what % of vision box is covered by element
                    val visionCoverage = interArea / targetArea
                    // Element coverage: what % of element is covered by vision box
                    val elementCoverage = interArea / elemArea

                    // Combined score: prefer elements that match the vision box size
                    // Uses geometric mean of coverages to penalize oversized containers
                    val coverageScore = kotlin.math.sqrt(visionCoverage * elementCoverage)
                    val score = maxOf(iou, coverageScore)

                    // No threshold - always take best match to avoid false negatives
                    if (bestMatch == null || score > bestMatch.second) {
                        val element = findElementByXPath(doc, xpath)
                        val dataId = element?.attr("data-ds-id")
                        if (dataId?.isNotEmpty() == true) {
                            bestMatch = Triple(dataId, score, iou)
                        }
                    }
                }
            }

            // Always return best match if found (no threshold)
            return if (bestMatch != null) {
                val scoreLabel = when {
                    bestMatch.second >= 0.8 -> "excellent"
                    bestMatch.second >= 0.6 -> "good"
                    bestMatch.second >= 0.4 -> "moderate"
                    else -> "low"
                }
                logger.debug(
                    "Semantic {} mapped to {} with {} score {}",
                    elementType, bestMatch.first, scoreLabel, "%.3f".format(bestMatch.second)
                )
                LlmSemanticResult(id = bestMatch.first, note = visionElement.label)
            } else {
                logger.debug("Could not map semantic {} (no overlapping elements)", elementType)
                null
            }
        }

        return SemanticIdentificationResponse(
            header = mapSingleElement(visionResponse.header, "header"),
            footer = mapSingleElement(visionResponse.footer, "footer"),
            navSidebar = mapSingleElement(visionResponse.navSidebar, "navSidebar"),
            breadcrumb = mapSingleElement(visionResponse.breadcrumb, "breadcrumb"),
            cookieBanner = mapSingleElement(visionResponse.cookieBanner, "cookieBanner"),
            popups = visionResponse.popups?.mapNotNull { mapSingleElement(it, "popup") } ?: emptyList()
        )
    }


    /**
     * Find element by XPath in the parsed document.
     * XPath format: ./tagname[index]/tagname[index]/...
     */
    private fun findElementByXPath(doc: Document, xpath: String): org.jsoup.nodes.Element? {
        // XPath format: ./tagname[index]/tagname[index]/...
        if (!xpath.startsWith("./")) return null

        val xpathRegex = Regex("""([a-zA-Z0-9_\-:]+)\[(\d+)]""")
        var current: org.jsoup.nodes.Element = doc.body() ?: return null

        val parts = xpath.removePrefix("./").split("/").filter { it.isNotEmpty() }
        for (part in parts) {
            val match = xpathRegex.matchEntire(part) ?: return null
            val tagName = match.groupValues[1]
            val index = match.groupValues[2].toInt() - 1 // XPath is 1-indexed

            val children = current.children().filter { it.tagName().equals(tagName, ignoreCase = true) }
            current = children.getOrNull(index) ?: return null
        }

        return current
    }

    private fun injectStableIdentifiers(html: String, prefix: String): String {
        val doc: Document = Jsoup.parse(html)
        var idCounter = 0

        // Only inject on structural elements relevant for semantic identification
        doc.select("header, footer, nav, aside, section, article, main, div").forEach { element ->
            element.attr("data-ds-id", "$prefix-${idCounter++}")
        }

        return doc.outerHtml()
    }

    /**
     * Clean HTML to reduce token count while preserving semantic structure.
     */
    private fun cleanHtml(rawHtml: String): String {
        val doc: Document = Jsoup.parse(rawHtml)

        // Step 1: Remove carousel/slider clones first (major source of duplication)
        doc.select(".slick-cloned, [class*=swiper-slide-duplicate], [data-cloned=true]").remove()

        // Step 1.5: Remove noise elements that don't contribute to structure
        doc.select(
            "script, style, noscript, template, svg, canvas, meta, link, iframe, object, embed, " +
                    "img, video, audio, source, track, picture"
        ).remove()

        // Remove comments and processing instructions
        doc.select("*").forEach { element ->
            val nodesToRemove = element.childNodes().filter { node ->
                node.nodeName() == "#comment" || node.nodeName() == "#pi"
            }
            nodesToRemove.forEach { node -> node.remove() }
        }

        // Step 2: Strip attributes aggressively
        doc.select("*").forEach { element ->
            val essentialAttrs = setOf("id", "class", "role", "aria-label", "aria-modal", "data-testid", "data-ds-id")
            val attrsToKeep = element.attributes().filter { attr ->
                attr.key in essentialAttrs
            }
            element.clearAttributes()
            attrsToKeep.forEach { attr ->
                element.attr(attr.key, attr.value)
            }
        }

        // Step 3: Minimize text content - only need structure for CSS selectors
        doc.select("*").forEach { element ->
            element.textNodes().forEach { textNode ->
                val text = textNode.text().trim()
                if (text.isNotEmpty()) {
                    val shortened = if (text.length > 15) text.take(15) + "..." else text
                    textNode.text(shortened)
                }
            }
        }

        // Step 3.5: Remove duplicate navigation structures (mobile/desktop variations)
        val mobileNavSelectors = listOf(
            ".mobile-nav", ".mobile-menu", "[class*=mobile-nav]", "[class*=mobile-menu]",
            ".nav-mobile", ".menu-mobile", "[id*=mobile-nav]", "[id*=mobile-menu]"
        )
        val desktopNavSelectors = listOf(
            ".desktop-nav", ".primary-nav", "[class*=desktop-nav]", "[class*=primary-nav]",
            ".nav-desktop", ".main-nav", "[id*=desktop-nav]", "[id*=primary-nav]"
        )

        val hasMobileNav = doc.select(mobileNavSelectors.joinToString(",")).isNotEmpty()
        val hasDesktopNav = doc.select(desktopNavSelectors.joinToString(",")).isNotEmpty()
        if (hasMobileNav && hasDesktopNav) {
            doc.select(mobileNavSelectors.joinToString(",")).remove()
            logger.debug("Removed duplicate mobile navigation (desktop nav exists)")
        }

        // Step 3.6: Prune deeply nested elements to reduce noise
        val maxDepth = 12
        doc.select("*").toList().forEach { element ->
            val depth = generateSequence(element) { it.parent() }.count()
            if (depth > maxDepth) {
                if (element.attr("id").isEmpty() && element.attr("class").isEmpty()) {
                    element.remove()
                }
            }
        }

        // Step 4: Remove empty elements iteratively
        var changed = true
        while (changed) {
            changed = false
            val emptyElements = doc.select("*").filter { element ->
                element.children().isEmpty() &&
                        element.ownText().isBlank() &&
                        element.attr("role").isEmpty() &&
                        element.attr("data-ds-id").isNotEmpty()
            }
            if (emptyElements.isNotEmpty()) {
                emptyElements.forEach { it.remove() }
                changed = true
            }
        }

        val cleanedHtml = doc.outerHtml()
        logger.debug("Cleaned HTML character count: {} (original: ~{})", cleanedHtml.length, rawHtml.length)
        return cleanedHtml
    }

    // ========== Batch Processing Methods ==========

    private val batchJson = Json { ignoreUnknownKeys = true }

    override fun prepareBatchRequest(
        requestId: String,
        html: String,
        screenshotBase64: String?,
        screenshotMimeType: String?,
        boundingBoxes: Map<String, IBrowserPage.BoundingBox>?,
        pageWidth: Double?,
        pageHeight: Double?
    ): SemanticIdentificationBatchRequest {
        val htmlWithIds = injectStableIdentifiers(html, "ds-semantic")

        // Use vision if screenshot is provided, otherwise fall back to HTML-based detection
        val request = if (screenshotBase64 != null && screenshotMimeType != null) {
            // Vision-based batch request (same as interactive mode)
            val metadata = mutableMapOf("useVision" to "true")
            if (pageWidth != null && pageHeight != null) {
                metadata["pageWidth"] = pageWidth.toString()
                metadata["pageHeight"] = pageHeight.toString()
            }

            BatchContentRequest(
                requestId = requestId,
                modelId = ModelIds.GEMINI_2_5_FLASH.modelId, // Vision model
                systemInstruction = visionSystemInstruction,
                userPrompt = "Analyze this screenshot",
                imageData = screenshotBase64,
                imageMimeType = screenshotMimeType,
                temperature = 0f,
                metadata = metadata
            ).withSchema(visionOutputSchema)
        } else {
            // HTML-based batch request (fallback)
            val cleanedHtml = cleanHtml(htmlWithIds)
            BatchContentRequest(
                requestId = requestId,
                modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId,
                systemInstruction = systemInstruction,
                userPrompt = cleanedHtml,
                temperature = 0f,
                metadata = mapOf("useVision" to "false")
            ).withSchema(outputSchema)
        }

        return SemanticIdentificationBatchRequest(
            request = request,
            htmlWithIds = htmlWithIds
        )
    }

    override fun parseBatchResponse(
        responseText: String,
        htmlWithIds: String,
        boundingBoxes: Map<String, IBrowserPage.BoundingBox>?,
        pageWidth: Double?,
        pageHeight: Double?
    ): SemanticElements {
        // Check if this was a vision request by trying to parse as VisionSemanticResponse
        val isVisionResponse = responseText.contains("\"topPercent\"") || responseText.contains("\"bottomPercent\"")

        return try {
            if (isVisionResponse && boundingBoxes != null) {
                parseBatchResponseVision(responseText, htmlWithIds, boundingBoxes, pageWidth, pageHeight)
            } else {
                parseBatchResponseHtml(responseText, htmlWithIds)
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse batch response: {}", e.message)
            SemanticElements()
        }
    }

    private fun parseBatchResponseHtml(responseText: String, htmlWithIds: String): SemanticElements {
        val response = batchJson.decodeFromString<SemanticIdentificationResponse>(responseText)

        // Collect all identifiers for batch processing
        val allLlmResults = listOfNotNull(
            response.header, response.footer, response.navSidebar,
            response.breadcrumb, response.cookieBanner
        ) + response.adBanners + response.popups

        val identifiers = allLlmResults.map { it.id }
        val cssSelectorsMap = cssSelectorConstructionService.constructCssSelectorsFromIdentifiers(
            identifiers = identifiers,
            htmlWithIdentifiers = htmlWithIds
        )

        fun resolveElement(llmResult: LlmSemanticResult?): IdentifiedElement? {
            if (llmResult == null) return null
            val cssSelector = cssSelectorsMap[llmResult.id] ?: return null
            return IdentifiedElement(
                cssSelector = cssSelector,
                dataId = llmResult.id,
                note = llmResult.note
            )
        }
        return SemanticElements(
            header = resolveElement(response.header),
            footer = resolveElement(response.footer),
            navSidebar = resolveElement(response.navSidebar),
            breadcrumb = resolveElement(response.breadcrumb),
            cookieBanner = resolveElement(response.cookieBanner),
            adBanners = response.adBanners.mapNotNull { resolveElement(it) },
            popups = response.popups.mapNotNull { resolveElement(it) }
        )
    }

    private fun parseBatchResponseVision(
        responseText: String,
        htmlWithIds: String,
        boundingBoxes: Map<String, IBrowserPage.BoundingBox>,
        pageWidth: Double?,
        pageHeight: Double?
    ): SemanticElements {
        val visionResponse = batchJson.decodeFromString<VisionSemanticResponse>(responseText)
        val doc = Jsoup.parse(htmlWithIds)

        // Get page dimensions
        val width = pageWidth ?: boundingBoxes.values.maxOfOrNull { it.right } ?: 1920.0
        val height = pageHeight ?: boundingBoxes.values.maxOfOrNull { it.bottom } ?: 1080.0

        // Map vision results to DOM using IoU
        fun mapVisionElement(visionElement: VisionSemanticElement?): IdentifiedElement? {
            if (visionElement == null) return null

            // Convert [0, 1000] scaled coordinates to absolute pixel coordinates
            // box_2d format: [ymin, xmin, ymax, xmax]
            val targetTop = visionElement.ymin * height / 1000
            val targetLeft = visionElement.xmin * width / 1000
            val targetBottom = visionElement.ymax * height / 1000
            val targetRight = visionElement.xmax * width / 1000
            val targetArea = (targetRight - targetLeft) * (targetBottom - targetTop)

            if (targetArea <= 0) return null

            // Find best matching element by IoU
            var bestMatch: Triple<String, Double, org.jsoup.nodes.Element?>? = null

            for ((xpath, elementBox) in boundingBoxes) {
                val elementLeft = elementBox.left
                val elementTop = elementBox.top
                val elementRight = elementBox.right
                val elementBottom = elementBox.bottom
                val elementArea = (elementRight - elementLeft) * (elementBottom - elementTop)

                if (elementArea <= 0) continue

                // Calculate IoU
                val intersectLeft = maxOf(targetLeft, elementLeft)
                val intersectTop = maxOf(targetTop, elementTop)
                val intersectRight = minOf(targetRight, elementRight)
                val intersectBottom = minOf(targetBottom, elementBottom)

                val intersectWidth = maxOf(0.0, intersectRight - intersectLeft)
                val intersectHeight = maxOf(0.0, intersectBottom - intersectTop)
                val intersectArea = intersectWidth * intersectHeight

                val unionArea = targetArea + elementArea - intersectArea
                val iou = if (unionArea > 0) intersectArea / unionArea else 0.0

                // Vision coverage: what % of vision box is covered by element
                val visionCoverage = intersectArea / targetArea
                // Element coverage: what % of element is covered by vision box
                val elementCoverage = intersectArea / elementArea

                // Combined score: prefer elements that match the vision box size
                // Uses geometric mean of coverages to penalize oversized containers
                val coverageScore = kotlin.math.sqrt(visionCoverage * elementCoverage)
                val score = maxOf(iou, coverageScore)

                // No threshold - always pick best match to avoid false negatives
                if (bestMatch == null || score > bestMatch.second) {
                    val element = findElementByXPath(doc, xpath)
                    if (element != null) {
                        bestMatch = Triple(xpath, score, element)
                    }
                }
            }

            // Always return best match if found (no threshold)
            if (bestMatch != null) {
                val element = bestMatch.third!!
                val dataId = element.attr("data-ds-id")
                if (dataId.isNotEmpty()) {
                    val cssSelector = cssSelectorConstructionService.constructCssSelector(element)
                    return IdentifiedElement(cssSelector, dataId, visionElement.label)
                }
            }
            return null
        }

        // Map all vision elements to DOM
        val header = mapVisionElement(visionResponse.header)
        val footer = mapVisionElement(visionResponse.footer)
        val navSidebar = mapVisionElement(visionResponse.navSidebar)
        val breadcrumb = mapVisionElement(visionResponse.breadcrumb)
        val cookieBanner = mapVisionElement(visionResponse.cookieBanner)
        val popups = visionResponse.popups?.mapNotNull { mapVisionElement(it) } ?: emptyList()

        // Apply programmatic fallback for header/footer if not found via vision
        val programmaticHeader = if (header == null) {
            doc.select("header, [role=banner]").firstOrNull()?.let {
                val dataId = it.attr("data-ds-id")
                if (dataId.isNotEmpty()) {
                    val cssSelector = cssSelectorConstructionService.constructCssSelector(it)
                    IdentifiedElement(cssSelector, dataId, "Programmatic header")
                } else null
            }
        } else header

        val programmaticFooter = if (footer == null) {
            doc.select("footer, [role=contentinfo]").firstOrNull()?.let {
                val dataId = it.attr("data-ds-id")
                if (dataId.isNotEmpty()) {
                    val cssSelector = cssSelectorConstructionService.constructCssSelector(it)
                    IdentifiedElement(cssSelector, dataId, "Programmatic footer")
                } else null
            }
        } else footer

        return SemanticElements(
            header = programmaticHeader,
            footer = programmaticFooter,
            navSidebar = navSidebar,
            breadcrumb = breadcrumb,
            cookieBanner = cookieBanner,
            adBanners = emptyList(), // Vision doesn't detect ad banners
            popups = popups
        )
    }

    // Keep old catch block for backwards compatibility
    private fun parseBatchResponseLegacy(responseText: String, htmlWithIds: String): SemanticElements {
        return try {
            parseBatchResponseHtml(responseText, htmlWithIds)
        } catch (e: Exception) {
            logger.warn("Failed to parse batch response: {}", e.message)
            SemanticElements()
        }
    }
}
