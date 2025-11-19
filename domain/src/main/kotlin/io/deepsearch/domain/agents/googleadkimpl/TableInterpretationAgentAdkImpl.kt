package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.ITableInterpretationAgent
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.agents.TableInterpretationOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class TableInterpretationAgentAdkImpl : ITableInterpretationAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Markdown representation of a tabular element")
        .properties(
            mapOf(
                "markdown" to Schema.builder()
                    .type("STRING")
                    .description("The table expressed in GitHub-flavored Markdown.")
                    .build()
            )
        )
        .required(listOf("markdown"))
        .build()

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("tableInterpretationAgent")
        description("Interpret a table region from a webpage and output Markdown")
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
            You are given the HTML markup for a table-like region from a webpage and an auxiliary info describing 
            the table. Convert this table into clean, faithful GitHub-flavored Markdown.
            
            Each element in the HTML has been augmented with a ds-bounding-box attribute containing spatial coordinates
            in the format ds-bounding-box="left top right bottom". These coordinates are relative to the table element's top-left corner
            and can help you understand the spatial layout and relationships between elements.

            Note that HTML tables may not be in perfect row/column format due to styling etc. Bounding box is crucial
            for mapping elements that are out of place. For these elements, you can simply add rows/column in the 
            markdown to accurately represent them.

            Rules:
            - Preserve the table's row and column structure and order accurately.
            - Include a header row if one exists; otherwise infer a sensible header from the first row if appropriate.
            - The HTML table may not translate well into a 2-dimensional markdown table. 
              In that case please adjust the rows and columns while preserving the semantic meaning of the table.
            - Use the bounding box coordinates to better understand the spatial layout when the HTML structure is ambiguous.
            - Do not invent data. Only use what is present in the supplied HTML.
            - Normalize whitespace; remove decorative or layout-only characters.
            - For merged cells, please duplicate the cell value to all corresponding cells in the markdown table.
            - Output only the Markdown string, wrapped in JSON structured output

            Expected output shape:
            {
                "markdown": "string"
            }
            """.trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    @Serializable
    private data class TableInterpretationResponse(
        val markdown: String
    )

    override suspend fun generate(input: TableInterpretationInput): TableInterpretationOutput {
        // Extract HTML and bounding boxes from the webpage
        val tableHtml = input.webpage.getElementHtmlByCssSelector(input.tableIdentification.cssSelector)
        val boundingBoxes = input.webpage.getBoundingBoxesByCssSelector(input.tableIdentification.cssSelector)
        
        logger.debug("Interpreting table to markdown (html length {})", tableHtml.length)
        logger.debug("Got {} bounding boxes", boundingBoxes.size)

        // Inject bounding box attributes into HTML
        val htmlWithBoundingBoxes = injectBoundingBoxes(tableHtml, boundingBoxes)

        val response = retryLlmCall<TableInterpretationResponse> {
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

            val parts = buildList {
                add(Part.fromText("Auxiliary context: " + input.tableIdentification.auxiliaryInfo))
                add(Part.fromText(htmlWithBoundingBoxes))
            }

            val eventsFlow = runner.runAsync(
                session,
                Content.fromParts(*parts.toTypedArray()),
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

        val markdown = response.markdown.trim()
        logger.debug("Table interpreted to markdown ({} chars)", markdown.length)

        return TableInterpretationOutput(markdown = markdown)
    }

    /**
     * Injects bounding box coordinates into HTML elements.
     * Each element receives a ds-bounding-box attribute with format "left top right bottom".
     */
    private fun injectBoundingBoxes(html: String, boundingBoxes: Map<String, io.deepsearch.domain.browser.IBrowserPage.BoundingBox>): String {
        if (boundingBoxes.isEmpty()) {
            return html
        }

        try {
            // Parse the HTML fragment (table element)
            val doc = Jsoup.parseBodyFragment(html)
            doc.outputSettings().prettyPrint(false)
            
            // The table should be the first child in the body
            val root = doc.body().children().firstOrNull() ?: return html
            
            for ((xpath, bbox) in boundingBoxes) {
                val bboxValue = "${bbox.left} ${bbox.top} ${bbox.right} ${bbox.bottom}"
                
                // XPath format: ./tagname[index]/tagname[index]/...
                val element = findElementByRelativeXPath(root, xpath)
                element?.attr("ds-bounding-box", bboxValue)
            }
            
            // Return only the body's inner HTML (the table element)
            return doc.body().html()
        } catch (e: Exception) {
            logger.warn("Failed to inject bounding boxes: {}", e.message)
            return html
        }
    }

    /**
     * Find an element using a relative XPath expression starting from a root element.
     * XPath format: ./tagname[index]/tagname[index]/... or "." for the root itself
     */
    private fun findElementByRelativeXPath(root: org.jsoup.nodes.Element, xpath: String): org.jsoup.nodes.Element? {
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
            val match = Regex("""(\w+)\[(\d+)\]""").find(part) ?: return null
            val tagName = match.groupValues[1]
            val index = match.groupValues[2].toInt()
            
            // Find the nth child with the matching tag name (1-based index)
            val children = current.children().filter { it.tagName().equals(tagName, ignoreCase = true) }
            if (index < 1 || index > children.size) {
                return null
            }
            
            current = children[index - 1]
        }
        
        return current
    }
}


