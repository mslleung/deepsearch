package io.deepsearch.domain.agents.googlegenaiimpl

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
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class SemanticIdentificationAgentGenAiImpl(
    private val client: com.google.genai.Client,
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
        // Step 1: Get webpage HTML and bounding boxes from snapshot
        val originalHtml = input.snapshot.html
        val boundingBoxes = input.snapshot.boundingBoxes
        
        logger.debug("Got {} bounding boxes for semantic identification", boundingBoxes.size)
        
        // Step 2: Inject stable identifiers into jsoup copy
        val htmlWithIds = injectStableIdentifiers(originalHtml, "ds-semantic")
        
        // Step 3: Clean HTML (after identifier injection)
        val cleanedHtml = cleanHtml(htmlWithIds)

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)
        
        if (cleanedHtml.isEmpty()) {
            return SemanticIdentificationOutput(
                elements = SemanticElements(),
                tokenUsage = tokenUsage
            )
        }

        // Step 4: Pass to LLM
        
        val response = retryLlmCall<SemanticIdentificationResponse>(this::class.simpleName!!) {
            val result = client.models.generateContent(
                modelId,
                cleanedHtml,
                GenerateContentConfig.builder()
                    .temperature(0F)
                    .responseSchema(outputSchema)
                    .responseMimeType("application/json")
                    .thinkingConfig(
                        ThinkingConfig.builder()
                            .thinkingBudget(0)
                            .build()
                    )
                    .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
                    .build()
            )

            result.checkFinishReason()
            
            // Extract token usage
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

        // Step 5: Reconstruct CSS selectors for all identified elements
        data class ResolvedElement(
            val llmResult: LlmSemanticResult,
            val cssSelector: String
        )
        
        fun resolveElement(llmResult: LlmSemanticResult?): ResolvedElement? {
            if (llmResult == null) return null
            
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
            return ResolvedElement(llmResult, cssSelector)
        }
        
        // Resolve all elements first (no browser calls)
        val resolvedHeader = resolveElement(response.header)
        val resolvedFooter = resolveElement(response.footer)
        val resolvedNavSidebar = resolveElement(response.navSidebar)
        val resolvedBreadcrumb = resolveElement(response.breadcrumb)
        val resolvedCookieBanner = resolveElement(response.cookieBanner)
        val resolvedAdBanners = response.adBanners.mapNotNull { resolveElement(it) }
        val resolvedPopups = response.popups.mapNotNull { resolveElement(it) }
        
        // Step 6: Batch inject all identifiers in a single CDP call
        val allResolved = listOfNotNull(
            resolvedHeader, resolvedFooter, resolvedNavSidebar,
            resolvedBreadcrumb, resolvedCookieBanner
        ) + resolvedAdBanners + resolvedPopups
        
        if (allResolved.isNotEmpty()) {
            val injections = allResolved.map { resolved ->
                IBrowserPage.AttributeInjection(
                    cssSelector = resolved.cssSelector,
                    attributeName = "data-ds-id",
                    attributeValue = resolved.llmResult.id
                )
            }
            logger.debug("Batch injecting {} semantic element identifiers", injections.size)
            input.webpage.injectAttributesByCssSelectors(injections)
        }
        
        // Convert resolved elements to IdentifiedElements
        fun toIdentifiedElement(resolved: ResolvedElement?): IdentifiedElement? {
            if (resolved == null) return null
            return IdentifiedElement(
                cssSelector = "[data-ds-id=\"${resolved.llmResult.id}\"]",
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
}


