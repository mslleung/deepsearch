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
        .description("An identified element with HTML snippet and note")
        .properties(
            mapOf(
                "htmlSnippet" to Schema.builder().type("STRING")
                    .description("A concise HTML snippet showing the opening tag and first ~10 lines of content, sufficient to uniquely identify the element").build(),
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
        description("Identify and return HTML snippets of all semantic elements (navigation elements, popups, etc.) using screenshot and cleaned HTML")
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
            Task: Identify popup and navigational elements on the page and provide their HTML snippet and a short note.

            Inputs:
            - A screenshot of the webpage (current viewport)
            - CLEANED HTML (subset of DOM with key attributes)

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
            - For each region, extract a concise HTML snippet: the opening tag with attributes and the first ~10 lines of nested content (just enough to uniquely identify the element)
            - Make sure the regions are unique with no overlap.
            - Write a brief note describing why it is considered a semantic element (e.g., "Top navbar with logo and menu", "Left sidebar with category links", "Cookie consent popup").
            - Return null for optional single elements if not found.
            - Return empty arrays for list elements if none found.

            Output structure:
            {
              "header": { "htmlSnippet": string, "note": string } | null,
              "footer": { "htmlSnippet": string, "note": string } | null,
              "navSidebar": { "htmlSnippet": string, "note": string } | null,
              "breadcrumb": { "htmlSnippet": string, "note": string } | null,
              "cookieBanner": { "htmlSnippet": string, "note": string } | null,
              "adBanners": [ { "htmlSnippet": string, "note": string }, ... ],
              "popups": [ { "htmlSnippet": string, "note": string }, ... ]
            }
            """.trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    @Serializable
    private data class LlmSemanticResult(
        val htmlSnippet: String,
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
        val cleanedHtml = cleanHtml(input.html)

        if (cleanedHtml.isEmpty()) {
            return SemanticIdentificationOutput(
                elements = SemanticElements()
            )
        }

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
                    Part.fromBytes(input.screenshotBytes, input.mimetype.value),
                    Part.fromText("CLEANED_HTML:\n$cleanedHtml")
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

        logger.debug("Semantic element identification found {} HTML snippets from LLM", 
            listOfNotNull(response.header, response.footer, response.navSidebar, response.breadcrumb, response.cookieBanner).size + 
            response.adBanners.size + response.popups.size)

        // Convert HTML snippets to CSS selectors for each element type
        // Handle the case where a snippet might match multiple elements (though rare for semantic elements)
        val headerElements = response.header?.let { convertToIdentifiedElements(it, cleanedHtml, input.html) } ?: emptyList()
        val footerElements = response.footer?.let { convertToIdentifiedElements(it, cleanedHtml, input.html) } ?: emptyList()
        val navSidebarElements = response.navSidebar?.let { convertToIdentifiedElements(it, cleanedHtml, input.html) } ?: emptyList()
        val breadcrumbElements = response.breadcrumb?.let { convertToIdentifiedElements(it, cleanedHtml, input.html) } ?: emptyList()
        val cookieBannerElements = response.cookieBanner?.let { convertToIdentifiedElements(it, cleanedHtml, input.html) } ?: emptyList()
        val adBannerElements = response.adBanners.flatMap { convertToIdentifiedElements(it, cleanedHtml, input.html) }
        val popupElements = response.popups.flatMap { convertToIdentifiedElements(it, cleanedHtml, input.html) }

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
     * Converts an LLM semantic result (HTML snippet + note) to a list of IdentifiedElements.
     * Uses the CSS selector construction service to find matching elements.
     * Returns a list because a snippet might match multiple elements in rare cases.
     */
    private fun convertToIdentifiedElements(
        llmResult: LlmSemanticResult,
        cleanedHtml: String,
        fullHtml: String
    ): List<IdentifiedElement> {
        val cssSelectors = cssSelectorConstructionService.constructCssSelectorsFromSnippet(
            llmResult.htmlSnippet, cleanedHtml, fullHtml
        )
        
        if (cssSelectors.isEmpty()) {
            logger.warn("Skipping semantic element with snippet '{}' - could not construct valid CSS selector", 
                llmResult.htmlSnippet.take(100))
            return emptyList()
        }
        
        return cssSelectors.map { cssSelector ->
            IdentifiedElement(
                cssSelector = cssSelector,
                note = llmResult.note
            )
        }
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

        // Step 2: Strip attributes to only essentials for semantic identification
        // Keep FULL page structure - LLM needs context to distinguish semantic elements from content
        // Keep id, class, role for CSS selector targeting and semantic meaning
        doc.select("*").forEach { element ->
            val essentialAttrs = setOf("id", "class", "role", "aria-label", "aria-modal", "data-testid")
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
                        element.attr("id").isEmpty() &&
                        element.attr("class").isEmpty() &&
                        element.attr("role").isEmpty()
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

