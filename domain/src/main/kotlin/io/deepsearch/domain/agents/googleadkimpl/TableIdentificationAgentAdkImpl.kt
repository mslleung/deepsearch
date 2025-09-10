package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.TableIdentificationInput
import io.deepsearch.domain.agents.TableIdentificationOutput
import io.deepsearch.domain.agents.infra.ModelIds
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class TableIdentificationAgentAdkImpl : ITableIdentificationAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("List of XPath selectors for table roots")
        .properties(
            mapOf(
                "tableXPaths" to Schema.builder()
                    .type("ARRAY")
                    .description("Array of XPath strings pointing to table roots and captions")
                    .items(Schema.builder().type("STRING").build())
                    .build()
            )
        )
        .required(listOf("tableXPaths"))
        .build()

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("tableIdentificationAgent")
        description("Identify tables in a webpage screenshot and return XPath selectors to their roots")
        model(ModelIds.GEMINI_2_5_PRO.modelId)
        outputSchema(outputSchema)
        disallowTransferToPeers(true)
        disallowTransferToParent(true)
        generateContentConfig(
            GenerateContentConfig.builder()
                .temperature(0.1F)
                .build()
        )
        instruction(
            """
            You are a table identification agent. Given a screenshot of a webpage, analyze it to identify any table-like structures, including traditional HTML tables, ARIA tables, visual tables made of divs/flexboxes, and auxiliary elements like captions.

            Return ONLY a JSON object with a "tableXPaths" array containing XPath selectors pointing to the root elements of each identified table, including captions if present.

            Expected output shape:
            {
              "tableXPaths": ["/html/body/div/table", "//div[@class='table-root']"]
            }

            Use precise XPath to the lowest ancestor that encompasses the entire table structure. If no tables are found, return an empty array.
            """.trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    @Serializable
    private data class TableIdentificationResponse(
        val tableXPaths: List<String>
    )

    override suspend fun generate(input: TableIdentificationInput): TableIdentificationOutput {
        logger.debug("Table identification for screenshot")

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
                Part.fromBytes(input.screenshotBytes, "image/jpeg")
            ),
            RunConfig.builder().apply {
                setStreamingMode(RunConfig.StreamingMode.NONE)
                setMaxLlmCalls(100)
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

        val response = Json.decodeFromString<TableIdentificationResponse>(llmResponse)

        logger.debug("Table identification found {} xpaths", response.tableXPaths.size)

        return TableIdentificationOutput(tableXPaths = response.tableXPaths)
    }
}