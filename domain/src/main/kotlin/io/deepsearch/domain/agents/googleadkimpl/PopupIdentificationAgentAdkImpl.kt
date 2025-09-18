package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import io.deepsearch.domain.agents.IPopupIdentificationAgent
import io.deepsearch.domain.agents.PopupIdentificationInput
import io.deepsearch.domain.agents.PopupIdentificationOutput
import io.deepsearch.domain.agents.infra.ModelIds
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Node
import org.jsoup.select.NodeVisitor
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class PopupIdentificationAgentAdkImpl : IPopupIdentificationAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Return a unique XPath selector to the dismiss button of a visible popup/cookie banner; null if none")
        .properties(
            mapOf(
                "dismissButtonXPath" to Schema.builder()
                    .type("STRING")
                    .description("Unique XPath selector targeting the clickable dismiss/accept/reject button for the popup. Null if no popup is present.")
                    .nullable(true)
                    .build(),
            )
        )
        .build()

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("popupIdentificationAgent")
        description("Identify and return the XPath of the dismiss button for cookie/pop-up consent using screenshot and cleaned HTML")
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
            Task: Find if a cookie consent or popup banner is visible and return a unique XPath to the clickable dismiss/accept/reject button.

            Inputs:
            - A screenshot of the webpage (current viewport)
            - CLEANED HTML (subset of DOM with key attributes)

            Guidelines:
            - Understand the website using the webpage and HTML
            - The XPath must point to the CLICKABLE BUTTON (e.g., <button>, <a>, or clickable div) that dismisses the popup.
            - If multiple popups/buttons exist, choose the most prominent and primary dismiss action (e.g., "Accept all", "Reject all", "I agree").
            - If no popup is visible, return null.

            Output structure:
            {
                "dismissButtonXPath": string?
            }
            """.trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    @Serializable
    private data class PopupIdentificationResponse(
        val dismissButtonXPath: String? = null
    )

    override suspend fun generate(input: PopupIdentificationInput): PopupIdentificationOutput {
        logger.debug("Popup dismissal XPath identification for screenshot + HTML")

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
                Part.fromText("CLEANED_HTML:\n" + cleanedHtml)
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

        val response = Json.decodeFromString<PopupIdentificationResponse>(llmResponse)
        val xpath = response.dismissButtonXPath?.takeIf { it.isNotBlank() }

        return PopupIdentificationOutput(dismissButtonXPath = xpath)
    }

    private fun cleanHtml(rawHtml: String): String {
        val doc = Jsoup.parse(rawHtml)

        // Remove non-essential tags
        doc.select("script, style, noscript, svg, path, img, video, source, picture, canvas, iframe").remove()

        // Remove comments
        doc.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                if (node is Comment) node.remove()
            }

            override fun tail(node: Node, depth: Int) { /* no-op */
            }
        })

        // Keep only a safe set of attributes useful for identification
        val allowedAttrs = setOf(
            "id", "class", "role", "aria-label", "aria-labelledby", "aria-describedby",
            "type", "name", "value", "href", "title"
        )

        doc.allElements.forEach { el ->
            val it = el.attributes().iterator()
            val toRemove = mutableListOf<String>()
            while (it.hasNext()) {
                val attr = it.next()
                val key = attr.key
                val isDataAttr = key.startsWith("data-")
                if (!allowedAttrs.contains(key) && !isDataAttr) {
                    toRemove.add(key)
                }
            }
            toRemove.forEach { key -> el.removeAttr(key) }
        }

        val bodyHtml = doc.body().outerHtml()
        val maxLen = 150_000
        return bodyHtml.take(maxLen)
    }
}
