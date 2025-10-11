package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.TableIdentification
import io.deepsearch.domain.agents.TableIdentificationInput
import io.deepsearch.domain.agents.TableIdentificationOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.decodeFromStringWithCodeBlocks
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class TableIdentificationAgentAdkImpl : ITableIdentificationAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("List of XPath selectors for table roots")
        .properties(
            mapOf(
                "tables" to Schema.builder()
                    .type("ARRAY")
                    .description("Array of XPath strings pointing to table roots and captions")
                    .items(
                        Schema.builder()
                            .type("OBJECT")
                            .properties(
                                mapOf(
                                    "xpath" to Schema.builder().type("STRING").description("The XPath to the table.")
                                        .build(),
                                    "auxiliaryInfo" to Schema.builder().type("STRING")
                                        .description("The auxiliary info for the table.").build()
                                )
                            )
                            .required(listOf("xpath", "auxiliaryInfo"))
                            .build()
                    )
                    .build()
            )
        )
        .required(listOf("tables"))
        .build()

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("tableIdentificationAgent")
        description("Identify tables in webpage using screenshot and cleaned HTML, return XPath selectors to their root containers")
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
            Your task is to identify all tables in the provided webpage and generate XPath queries to their root containers.

            Inputs:
            - A screenshot of the full webpage
            - Cleaned HTML structure of the webpage

            Instructions:
            - Analyze both the screenshot and HTML to identify every table. A "table" is any data presented in a structured, grid-like format (rows and columns).
            - Look for both standard HTML table elements (<table>, <tr>, <td>, <th>) and CSS-based table layouts (divs with table-like styling).
            - For every table you find, create an XPath selector that targets the ROOT CONTAINER element that wraps the entire table.
            - Each XPath selector should uniquely identify a single table root container in the webpage.
            - Additionally, extract auxiliaryInfo using surrounding text such as table headers and captions to provide extra information for understanding the table.

            Example XPath (targets the root container of the table):
            //*[contains(., 'Standard') and contains(., '30mins') and contains(., 'Follow')]

            Expected output shape:
            {
                "tables": [
                    {
                        "xpath": "string",
                        "auxiliaryInfo": "string"
                    }
                ]
            }
            """.trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    @Serializable
    private data class TableIdentificationResponse(
        val tables: List<TableIdentification>
    )

    override suspend fun generate(input: TableIdentificationInput): TableIdentificationOutput {
        logger.debug("Table identification for HTML (screenshot: {} bytes)", input.screenshotBytes.size)

        val cleanedHtml = cleanHtml(input.html)

        if (cleanedHtml.isEmpty()) {
            return TableIdentificationOutput(tables = emptyList())
        }

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

        val response = Json.decodeFromStringWithCodeBlocks<TableIdentificationResponse>(llmResponse)

        logger.debug("Table identification found {} tables", response.tables.size)

        return TableIdentificationOutput(tables = response.tables)
    }

    private fun cleanHtml(rawHtml: String): String {
        val doc: Document = Jsoup.parse(rawHtml)

        // Step 1: Find all potential table elements
        val tableCandidates = doc.select(
            // HTML table elements
            "table, " +
            // ARIA table roles
            "[role=table], [role=grid], " +
            // Common table patterns in IDs (case variations)
            "[id*=table], [id*=Table], [id*=TABLE], " +
            "[id*=grid], [id*=Grid], [id*=data], [id*=Data], " +
            // Common table patterns in classes
            "[class*=table], [class*=Table], " +
            "[class*=grid], [class*=Grid], " +
            "[class*=data], [class*=Data], " +
            // Divs that might be CSS-based tables (containers with grid-like structure)
            // Look for divs with multiple child divs (potential grid layout)
            "div:has(> div > div)"
        )

        logger.debug("Found {} table candidates before filtering", tableCandidates.size)

        if (tableCandidates.isEmpty()) {
            logger.debug("No table candidates found, returning empty HTML")
            return ""
        }

        // Step 2: Build a set of elements to KEEP (candidates + their context)
        val elementsToKeep = mutableSetOf<Element>()
        tableCandidates.forEach { candidate ->
            elementsToKeep.add(candidate)
            // Keep all descendants of table candidates (entire table structure needed)
            elementsToKeep.addAll(candidate.select("*"))
            // Keep ancestors up to body for XPath structure
            var parent = candidate.parent()
            while (parent != null && parent.tagName() != "body") {
                elementsToKeep.add(parent)
                parent = parent.parent()
            }
            // Keep previous and next siblings for auxiliary context (captions, headers)
            candidate.previousElementSibling()?.let { sibling ->
                elementsToKeep.add(sibling)
                elementsToKeep.addAll(sibling.select("*"))
            }
            candidate.nextElementSibling()?.let { sibling ->
                elementsToKeep.add(sibling)
                elementsToKeep.addAll(sibling.select("*"))
            }
        }

        logger.debug("Keeping {} elements (candidates + descendants + ancestors + siblings)", elementsToKeep.size)

        // Step 3: Remove everything NOT in the keep set
        doc.body().select("*").toList().forEach { element ->
            if (element !in elementsToKeep) {
                element.remove()
            }
        }

        // Step 4: Remove obvious non-table elements even from kept elements
        doc.select(
            "script, style, noscript, template, svg, canvas, meta, link, iframe, object, embed, " +
            "head, title, base, form, input, button, select, textarea, " +
            "nav, header, footer, aside, " +
            "img, video, audio, source, track, picture"
        ).remove()

        // Remove comments and processing instructions
        doc.select("*").forEach { element ->
            val nodesToRemove = element.childNodes().filter { node ->
                node.nodeName() == "#comment" || node.nodeName() == "#pi"
            }
            nodesToRemove.forEach { node -> node.remove() }
        }

        // Step 5: Keep only structural attributes essential for table identification
        doc.select("*").forEach { element ->
            val essentialAttrs = setOf("id", "class", "role", "colspan", "rowspan", "scope")
            val attrsToKeep = element.attributes().filter { attr ->
                attr.key in essentialAttrs
            }
            element.clearAttributes()
            attrsToKeep.forEach { attr ->
                element.attr(attr.key, attr.value)
            }
        }

        // Step 6: Truncate text content but keep more than navigation (need context for auxiliary info)
        doc.select("*").forEach { element ->
            element.textNodes().forEach { textNode ->
                val text = textNode.text().replace("\\s+".toRegex(), " ").trim()
                if (text.isNotEmpty()) {
                    // Keep up to 40 chars per text node for context (table headers, captions)
                    val shortened = if (text.length > 40) text.take(40) + "..." else text
                    textNode.text(shortened)
                }
            }
        }

        // Step 7: Remove empty elements iteratively
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