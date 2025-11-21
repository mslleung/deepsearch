package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.agents.ISemanticIdentificationAgent
import io.deepsearch.domain.agents.SemanticIdentificationInput
import io.deepsearch.domain.agents.SemanticIdentificationOutput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.IdentifiedElement
import io.deepsearch.domain.models.valueobjects.SemanticElements
import io.deepsearch.domain.services.ICssSelectorConstructionService
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class SemanticIdentificationAgentAdkImpl(
    private val cssSelectorConstructionService: ICssSelectorConstructionService
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

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("semanticIdentificationAgent")
        description("Identify and return stable IDs of all semantic elements (navigation elements, popups, etc.) using the cleaned HTML")
        model(ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId)
        outputSchema(outputSchema)
        disallowTransferToPeers(true)
        disallowTransferToParent(true)
        generateContentConfig(
            GenerateContentConfig.builder()
                .temperature(0F)
                .thinkingConfig(
                    ThinkingConfig.builder()
                        .thinkingBudget(0)
                        .build()
                )
                .build()
        )
        instruction(
            """
            Task: Identify popup and navigational elements on the page and return their stable identifiers and a short note.

            Inputs:
            - CLEANED HTML (subset of DOM with key attributes, all potential semantic elements have data-ds-id attributes)
            
            Each element in the HTML has been augmented with a ds-bounding-box attribute containing spatial coordinates
            in the format ds-bounding-box="left top right bottom". These coordinates help you understand the spatial layout
            and relationships between elements.

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
            - Use the bounding box coordinates to better understand spatial layout when the HTML structure is ambiguous.
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
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

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
        // Step 1: Get webpage HTML and bounding boxes
        val originalHtml = input.webpage.getFullHtml()
        val boundingBoxes = input.webpage.getBoundingBoxesByCssSelector("body")
        
        logger.debug("Got {} bounding boxes for semantic identification", boundingBoxes.size)
        
        // Step 2: Inject stable identifiers into jsoup copy
        val htmlWithIds = injectStableIdentifiers(originalHtml, "ds-semantic")
        
        // Step 3: Inject bounding boxes into jsoup copy
//        val htmlWithIdsAndBboxes = injectBoundingBoxes(htmlWithIds, boundingBoxes)
        
        // Step 4: Clean HTML (after identifier and bbox injection)
        val cleanedHtml = cleanHtml(htmlWithIds)

        if (cleanedHtml.isEmpty()) {
            return SemanticIdentificationOutput(
                elements = SemanticElements()
            )
        }

        // Step 5: Pass to LLM
        val response = retryLlmCall<SemanticIdentificationResponse> {
            val session = runner
                .sessionService()
                .createSession(
                    this::class.simpleName,
                    this::class.simpleName,
                    null,
                    null
                )
                .await()

            var llmResponse = ""

            val eventsFlow = runner.runAsync(
                session,
                Content.fromParts(
                    Part.fromText(cleanedHtml)
                ),
                RunConfig.builder().apply {
                    setStreamingMode(RunConfig.StreamingMode.NONE)
                    setMaxLlmCalls(1)
                }.build()
            ).asFlow()

            eventsFlow.collect { event ->
                if (event.finalResponse() && event.content().isPresent) {
                    val content = event.content().get()
                    if (content.parts().isPresent
                        && !content.parts().get().isEmpty()
                        && content.parts().get()[0].text().isPresent
                    ) {
                        if (!event.partial().orElse(false)) {
                            llmResponse = content.parts().get()[0].text().get()
                        }
                    }
                }
            }

            llmResponse
        }

        logger.debug(
            "Semantic element identification found {} element IDs from LLM",
            listOfNotNull(
                response.header,
                response.footer,
                response.navSidebar,
                response.breadcrumb,
                response.cookieBanner
            ).size +
                    response.adBanners.size + response.popups.size
        )

        // Step 6 & 7: Reconstruct CSS selectors and inject into webpage
        val headerElements =
            response.header?.let { convertToIdentifiedElement(it, htmlWithIds, input) }?.let { listOf(it) }
                ?: emptyList()
        val footerElements =
            response.footer?.let { convertToIdentifiedElement(it, htmlWithIds, input) }?.let { listOf(it) }
                ?: emptyList()
        val navSidebarElements =
            response.navSidebar?.let { convertToIdentifiedElement(it, htmlWithIds, input) }?.let { listOf(it) }
                ?: emptyList()
        val breadcrumbElements =
            response.breadcrumb?.let { convertToIdentifiedElement(it, htmlWithIds, input) }?.let { listOf(it) }
                ?: emptyList()
        val cookieBannerElements =
            response.cookieBanner?.let { convertToIdentifiedElement(it, htmlWithIds, input) }?.let { listOf(it) }
                ?: emptyList()
        val adBannerElements =
            response.adBanners.mapNotNull { convertToIdentifiedElement(it, htmlWithIds, input) }
        val popupElements =
            response.popups.mapNotNull { convertToIdentifiedElement(it, htmlWithIds, input) }

        logger.debug("Semantic element identification complete")

        return SemanticIdentificationOutput(
            elements = SemanticElements(
                header = headerElements.firstOrNull(),
                footer = footerElements.firstOrNull(),
                navSidebar = navSidebarElements.firstOrNull(),
                breadcrumb = breadcrumbElements.firstOrNull(),
                cookieBanner = cookieBannerElements.firstOrNull(),
                adBanners = adBannerElements,
                popups = popupElements
            )
        )
    }

    /**
     * Converts an LLM semantic result (ID + note) to an IdentifiedElement.
     * Uses the ID to construct a CSS selector, injects the identifier into the actual webpage,
     * and returns a selector that points to the injected identifier.
     */
    private suspend fun convertToIdentifiedElement(
        llmResult: LlmSemanticResult,
        htmlWithIds: String,
        input: SemanticIdentificationInput
    ): IdentifiedElement? {
        // Step 6: Reconstruct CSS selector using identifier
        val cssSelector = cssSelectorConstructionService.constructCssSelectorFromIdentifier(
            identifier = llmResult.id,
            htmlWithIdentifiers = htmlWithIds
        )

        if (cssSelector == null) {
            logger.warn(
                "Skipping semantic element with ID '{}' - could not construct valid CSS selector",
                llmResult.id
            )
            return null
        }

        logger.debug("Constructed CSS selector '{}' for identifier '{}'", cssSelector, llmResult.id)

        // Step 7: Inject identifier into actual webpage
        input.webpage.injectAttributeByCssSelector(cssSelector, "data-ds-id", llmResult.id)

        // Return selector that matches the injected identifier
        return IdentifiedElement(
            cssSelector = "[data-ds-id=\"${llmResult.id}\"]",
            note = llmResult.note
        )
    }

    private fun cleanHtml(rawHtml: String): String {
        val doc: Document = Jsoup.parse(rawHtml)

        // Step 1: Remove carousel/slider clones first (major source of duplication)
        // Slick carousel, Swiper, and similar libraries clone slides for infinite scrolling
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

        // Step 2: Strip attributes aggressively - bounding boxes replace need for class/id
        // Keep only: role, aria-label, aria-modal, data-testid, ds-bounding-box
        doc.select("*").forEach { element ->
            val essentialAttrs = setOf("id", "class", "role", "aria-label", "aria-modal", "data-testid", "data-ds-id", "ds-bounding-box")
            val attrsToKeep = element.attributes().filter { attr ->
                attr.key in essentialAttrs
            }
            element.clearAttributes()
            attrsToKeep.forEach { attr ->
                element.attr(attr.key, attr.value)
            }
        }

        // Step 3: Aggressively minimize text content - only need structure for CSS selectors
        // Keep only first few chars per text node for context
        doc.select("*").forEach { element ->
            element.textNodes().forEach { textNode ->
                val text = textNode.text().trim()
                if (text.isNotEmpty()) {
                    // Keep minimal text for context (helps LLM understand element purpose)
                    // 15 chars is enough to understand "Home", "Login", "Products", "Accept Cookies", etc.
                    val shortened = if (text.length > 15) text.take(15) + "..." else text
                    textNode.text(shortened)
                }
            }
        }

        // Step 3.5: Remove duplicate navigation structures (mobile/desktop variations)
        // Many sites have separate mobile and desktop navigation with similar content
        val mobileNavSelectors = listOf(
            ".mobile-nav", ".mobile-menu", "[class*=mobile-nav]", "[class*=mobile-menu]",
            ".nav-mobile", ".menu-mobile", "[id*=mobile-nav]", "[id*=mobile-menu]"
        )
        val desktopNavSelectors = listOf(
            ".desktop-nav", ".primary-nav", "[class*=desktop-nav]", "[class*=primary-nav]",
            ".nav-desktop", ".main-nav", "[id*=desktop-nav]", "[id*=primary-nav]"
        )

        // If both mobile and desktop nav exist, remove mobile (desktop usually has better structure)
        val hasMobileNav = doc.select(mobileNavSelectors.joinToString(",")).isNotEmpty()
        val hasDesktopNav = doc.select(desktopNavSelectors.joinToString(",")).isNotEmpty()
        if (hasMobileNav && hasDesktopNav) {
            doc.select(mobileNavSelectors.joinToString(",")).remove()
            logger.debug("Removed duplicate mobile navigation (desktop nav exists)")
        }

        // Step 3.6: Prune deeply nested elements to reduce noise
        // Navigation elements are typically at higher levels of the DOM tree
        // Deep nesting (>12 levels) is usually content, not navigation structure
        val maxDepth = 12
        doc.select("*").toList().forEach { element ->
            val depth = generateSequence(element) { it.parent() }.count()
            if (depth > maxDepth) {
                // For deep elements, keep only if they have id/class (potential targets)
                if (element.attr("id").isEmpty() && element.attr("class").isEmpty()) {
                    element.remove()
                }
            }
        }

        // Step 4: Remove empty elements iteratively to compact structure
        var changed = true
        while (changed) {
            changed = false
            val emptyElements = doc.select("*").filter { element ->
                element.children().isEmpty() &&
                        element.ownText().isBlank() &&
                        element.attr("role").isEmpty() &&
                        element.attr("ds-bounding-box").isEmpty()
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

    /**
     * Injects stable identifiers into potential semantic elements for LLM processing.
     *
     * @param cleanedHtml The cleaned HTML (without IDs)
     * @param idPrefix The prefix for generated IDs (e.g., "ds-semantic")
     * @return HTML with injected data-ds-id attributes
     */
    private fun injectStableIdentifiers(cleanedHtml: String, idPrefix: String): String {
        val doc: Document = Jsoup.parse(cleanedHtml)

        var idCounter = 0
        doc.select("header, footer, nav, aside, section, article, main, div").forEach { element ->
            element.attr("data-ds-id", "$idPrefix-${idCounter++}")
        }

        return doc.outerHtml()
    }

    /**
     * Injects bounding box coordinates into HTML elements.
     * Each element receives a ds-bounding-box attribute with format "left top right bottom".
     * Only injects on structural elements relevant for semantic identification.
     */
    private fun injectBoundingBoxes(
        html: String,
        boundingBoxes: Map<String, IBrowserPage.BoundingBox>
    ): String {
        if (boundingBoxes.isEmpty()) {
            return html
        }

        try {
            // Parse the HTML
            val doc = Jsoup.parse(html)
            doc.outputSettings().prettyPrint(false)

            // The body should be the root
            val root = doc.body()

            // Pre-compile regex for performance
            val xpathRegex = Regex("""([a-zA-Z0-9_\-:]+)\[(\d+)\]""")
            
            // Tags relevant for semantic identification (structural/navigational elements)
            val relevantTags = setOf("header", "footer", "nav", "aside", "section", "article", "main", "div")

            for ((xpath, bbox) in boundingBoxes) {
                val width = bbox.right - bbox.left
                val height = bbox.bottom - bbox.top

                // If the element truly has 0 0 0 0 bounding box (or 0 size), do not inject as it has no meaning
                if (width <= 0.0 && height <= 0.0) {
                    continue
                }

                val bboxValue = "${bbox.left.toInt()} ${bbox.top.toInt()} ${bbox.right.toInt()} ${bbox.bottom.toInt()}"

                // XPath format: ./tagname[index]/tagname[index]/...
                val element = findElementByRelativeXPath(root, xpath, xpathRegex)
                
                // Only inject bounding boxes on relevant structural elements
                if (element != null && element.tagName() in relevantTags) {
                    element.attr("ds-bounding-box", bboxValue)
                }
            }

            // Return the full HTML
            return doc.outerHtml()
        } catch (e: Exception) {
            logger.warn("Failed to inject bounding boxes: {}", e.message)
            return html
        }
    }

    /**
     * Find an element using a relative XPath expression starting from a root element.
     * XPath format: ./tagname[index]/tagname[index]/... or "." for the root itself
     */
    private fun findElementByRelativeXPath(
        root: org.jsoup.nodes.Element,
        xpath: String,
        regex: Regex
    ): org.jsoup.nodes.Element? {
        if (xpath == ".") {
            return root
        }

        if (!xpath.startsWith("./")) {
            return null
        }

        val parts = xpath.substring(2).split("/")
        var current = root

        for (part in parts) {
            // Parse "tagname[index]"
            val match = regex.find(part) ?: return null
            val tagName = match.groupValues[1]
            val index = match.groupValues[2].toInt()

            // Find the nth child with the matching tag name (1-based index)
            // Optimize: traverse children manually to avoid creating new lists
            var count = 0
            var found: org.jsoup.nodes.Element? = null

            val children = current.children()
            for (child in children) {
                if (child.tagName().equals(tagName, ignoreCase = true)) {
                    count++
                    if (count == index) {
                        found = child
                        break
                    }
                }
            }

            if (found == null) {
                return null
            }
            current = found
        }

        return current
    }
}
