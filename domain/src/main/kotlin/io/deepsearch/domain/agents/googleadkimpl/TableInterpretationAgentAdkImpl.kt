package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import io.deepsearch.domain.agents.ITableInterpretationAgent
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.agents.TableInterpretationOutput
import io.deepsearch.domain.agents.infra.ModelIds
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
                    .description("The table expressed in GitHub-flavored Markdown. No surrounding commentary.")
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
                .build()
        )
        instruction(
            """
            You are given a cropped screenshot of a table-like region from a webpage and an auxiliary info describing 
            the table, along with the exact HTML markup for that element.
            Convert this table into clean, faithful GitHub-flavored Markdown.

            Rules:
            - Preserve the table's row and column structure accurately.
            - Include a header row if one exists; otherwise infer a sensible header from the first row if appropriate.
            - Do not invent data. Only use what is visible in the screenshot or present in the supplied HTML.
            - Normalize whitespace; remove decorative or layout-only characters.
            - Keep content concise; avoid verbose prose or explanations.
            - For merged cells, please duplicate the cell value to all corresponding cells in the markdown table.
            - Output only the Markdown string. Do not include any extra commentary before or after it.

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
        logger.debug("Interpreting table to markdown ({} bytes, html length {})", input.screenshotBytes.size, input.html.length)

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
                Part.fromText("Auxiliary context: " + input.auxiliaryInfo),
                Part.fromText(input.html),
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

        val response = Json.decodeFromString<TableInterpretationResponse>(llmResponse)

        val markdown = response.markdown.trim()
        logger.debug("Table interpreted to markdown ({} chars)", markdown.length)

        return TableInterpretationOutput(markdown = markdown)
    }
}


