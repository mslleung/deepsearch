package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.TableIdentification
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
        description("Identify tables in a webpage screenshot and return XPath selectors to their roots")
        model(ModelIds.GEMINI_2_5_LITE.modelId)
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
            Your task is to identify all tables in a webpage and generate XPath queries to those tables.

            Input:
            A screenshot of a webpage.

            Instructions:
            - Scan the entire webpage and identify every table. A "table" is any data presented in a structured, grid-like format (rows and columns).
            - For every table you find, create an XPath selector using words from within the table. Use the XPath "contains" operator to select words from multiple cells to ensure uniqueness.
            - The selected words should include content from different parts of the table, and must include some words from the table's first and last rows.
            - Each "contains" selector should match only one word with no spaces.
            - The XPath selector should point to the lowest common ancestor element that contains the entire table.
            - Do not make any assumption on the underlying HTML. Use * to match all possible tags that the table uses (e.g., <table>, <div>, <ul>). This is crucial for identifying tables not made with standard <table> tags.
            - Each XPath selector should uniquely identify a single table in the webpage.
            - Additionally, extract auxiliaryInfo using surrounding text such as a table header and caption to provide extra information for understanding the table.

            Example:
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
                Part.fromBytes(input.screenshotBytes, input.mimetype.value)
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

        val response = Json.decodeFromString<TableIdentificationResponse>(llmResponse)

        logger.debug("Table identification found {} tables", response.tables.size)

        return TableIdentificationOutput(tables = response.tables)
    }
}