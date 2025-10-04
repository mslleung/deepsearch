package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.INavigationElementIdentificationAgent
import io.deepsearch.domain.agents.NavigationElementIdentificationInput
import io.deepsearch.domain.agents.NavigationElementIdentificationOutput
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class NavigationElementIdentificationAgentAdkImpl : INavigationElementIdentificationAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Return XPath selectors to header and footer navigation elements")
        .properties(
            mapOf(
                "headerXPath" to Schema.builder()
                    .type("STRING")
                    .description("XPath selector targeting the main header/navigation container. Null if no header is present.")
                    .nullable(true)
                    .build(),
                "footerXPath" to Schema.builder()
                    .type("STRING")
                    .description("XPath selector targeting the main footer container. Null if no footer is present.")
                    .nullable(true)
                    .build()
            )
        )
        .build()

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("navigationElementIdentificationAgent")
        description("Identify and return XPath selectors of header and footer navigation elements using screenshot and cleaned HTML")
        model(ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId)
        outputSchema(outputSchema)
        disallowTransferToPeers(true)
        disallowTransferToParent(true)
        generateContentConfig(
            GenerateContentConfig.builder()
                .temperature(0F)
                .build()
        )
        instruction(
            """
            Task: Identify the main header and footer navigation elements on the webpage and return their XPaths.

            Inputs:
            - A screenshot of the webpage
            - CLEANED HTML (subset of DOM with key attributes)

            Guidelines:
            - Understand the website visually using the webpage screenshot
            - Identify the MAIN HEADER element (typically at the top, contains site navigation, logo, menu)
            - Identify the MAIN FOOTER element (typically at the bottom, contains copyright, links, contact info)
            - For each element, identify the ROOT CONTAINER that wraps the entire header or footer
            - Return XPath selectors that point to these root container elements
            - If no header or footer is visible, return null for that field

            Output structure:
            {
                "headerXPath": "string or null",
                "footerXPath": "string or null"
            }
            """.trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    @Serializable
    private data class NavigationElementIdentificationResponse(
        val headerXPath: String? = null,
        val footerXPath: String? = null
    )

    override suspend fun generate(input: NavigationElementIdentificationInput): NavigationElementIdentificationOutput {
        val cleanedHtml = cleanHtml(input.html)

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

        val response = Json.decodeFromString<NavigationElementIdentificationResponse>(llmResponse)
        
        val normalizedHeaderXPath = response.headerXPath?.let { normalizeXPath(it) }
        val normalizedFooterXPath = response.footerXPath?.let { normalizeXPath(it) }

        return NavigationElementIdentificationOutput(
            headerXPath = normalizedHeaderXPath,
            footerXPath = normalizedFooterXPath
        )
    }

    /**
     * Normalizes XPath expressions to ensure they work correctly with Playwright.
     * 
     * Common issues fixed:
     * - Converts absolute paths like `/div[@id='x']` to relative paths `//div[@id='x']`
     *   (since `/div` expects div to be a direct child of the document root, which is invalid for HTML)
     * - Preserves valid absolute paths like `/html/body/div[@id='x']`
     */
    private fun normalizeXPath(xpath: String): String {
        val trimmed = xpath.trim()
        
        // If XPath starts with a single `/` followed by something other than `html`, convert to `//`
        // This handles cases like `/div[@id='x']` -> `//div[@id='x']`
        if (trimmed.startsWith("/") && !trimmed.startsWith("//") && !trimmed.startsWith("/html")) {
            val normalized = "/$trimmed"
            logger.debug("Normalized XPath from '{}' to '{}'", trimmed, normalized)
            return normalized
        }
        
        return trimmed
    }

    private fun cleanHtml(rawHtml: String): String {
        val doc: Document = Jsoup.parse(rawHtml)

        // Remove scripts, styles, and other non-visual elements
        doc.select("script, style, noscript, meta, link[rel=stylesheet]").remove()

        // Focus on elements that commonly contain headers and footers
        val relevantElements = doc.select(
            "header, footer, nav, div, section, aside, main, article, " +
            "[role=banner], [role=navigation], [role=contentinfo], " +
            ".header, .footer, .nav, .navigation, " +
            "[class*=header], [class*=footer], [class*=nav], " +
            "[id*=header], [id*=footer], [id*=nav]"
        )

        // Build simplified HTML with key attributes
        val sb = StringBuilder()
        for (element in relevantElements) {
            appendElementInfo(element, sb, 0)
        }

        return sb.toString().take(8000) // Limit size for LLM processing
    }

    private fun appendElementInfo(element: Element, sb: StringBuilder, depth: Int) {
        if (depth > 10) return // Prevent infinite recursion

        val indent = "  ".repeat(depth)
        val tagName = element.tagName()
        val id = element.attr("id")
        val className = element.attr("class")
        val role = element.attr("role")
        val style = element.attr("style")

        sb.append("$indent<$tagName")
        if (id.isNotEmpty()) sb.append(" id=\"$id\"")
        if (className.isNotEmpty()) sb.append(" class=\"$className\"")
        if (role.isNotEmpty()) sb.append(" role=\"$role\"")
        if (style.isNotEmpty()) sb.append(" style=\"${style.take(100)}\"")
        sb.append(">\n")

        // Only include text content for small elements to avoid noise
        val text = element.ownText().trim()
        if (text.isNotEmpty() && text.length < 200) {
            sb.append("$indent  $text\n")
        }

        // Recursively process children (limited depth)
        element.children().take(20).forEach { child ->
            appendElementInfo(child, sb, depth + 1)
        }

        sb.append("$indent</$tagName>\n")
    }
}

