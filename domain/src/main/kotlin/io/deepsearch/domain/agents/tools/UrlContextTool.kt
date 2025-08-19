package io.deepsearch.domain.agents.tools

import com.google.adk.models.LlmRequest
import com.google.adk.tools.BaseTool
import com.google.adk.tools.ToolContext
import com.google.common.collect.ImmutableList
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Tool
import com.google.genai.types.UrlContext
import io.reactivex.rxjava3.core.Completable
import java.util.function.Function

/**
 * A tool for trying out URL context from Google.
 *
 * This will likely be replaced when the official implementation comes out.
 */
class UrlContextTool : BaseTool("url_context", "url_context") {

    override fun processLlmRequest(
        llmRequestBuilder: LlmRequest.Builder, toolContext: ToolContext?
    ): Completable {
        val configBuilder =
            llmRequestBuilder
                .build()
                .config()
                .map(Function { obj: GenerateContentConfig? -> obj!!.toBuilder() })
                .orElse(GenerateContentConfig.builder())

        val existingTools = configBuilder.build().tools().orElse(ImmutableList.of())
        val updatedToolsBuilder = ImmutableList.builder<Tool?>()
        updatedToolsBuilder.addAll(existingTools)

        val model = llmRequestBuilder.build().model().get()
        if (model.startsWith("gemini-1")) {
            if (!updatedToolsBuilder.build().isEmpty()) {
                println(configBuilder.build().tools().get())
                return Completable.error(
                    IllegalArgumentException(
                        "Url context tool cannot be used with other tools in Gemini 1.x."
                    )
                )
            }
            updatedToolsBuilder.add(
                Tool.builder().urlContext(UrlContext.builder().build()).build()
            )
            configBuilder.tools(updatedToolsBuilder.build())
        } else if (model.startsWith("gemini-2")) {
            updatedToolsBuilder.add(Tool.builder().urlContext(UrlContext.builder().build()).build())
            configBuilder.tools(updatedToolsBuilder.build())
        } else {
            return Completable.error(
                IllegalArgumentException("Url context tool is not supported for model " + model)
            )
        }

        llmRequestBuilder.config(configBuilder.build())
        return Completable.complete()
    }
}