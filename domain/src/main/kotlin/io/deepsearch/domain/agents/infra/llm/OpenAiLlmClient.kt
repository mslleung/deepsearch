package io.deepsearch.domain.agents.infra.llm

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.models.ChatModel
import com.openai.models.ResponseFormatJsonSchema
import com.openai.models.chat.completions.ChatCompletionContentPart
import com.openai.models.chat.completions.ChatCompletionContentPartImage
import com.openai.models.chat.completions.ChatCompletionContentPartText
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * [ILlmClient] adapter that calls Vertex AI MaaS open models via the OpenAI-compatible
 * Chat Completions endpoint using the official openai-java SDK.
 *
 * Handles translation between GenAI SDK types and OpenAI API format, including:
 * - Content/Part -> OpenAI messages (text, image base64)
 * - GenAI Schema -> OpenAI response_format.json_schema
 * - GCP OAuth2 bearer token authentication (via [MaasAuthProvider])
 */
class OpenAiLlmClient(
    private val baseUrl: String,
    private val authProvider: MaasAuthProvider,
) : ILlmClient {

    private val logger = LoggerFactory.getLogger(this::class.java)

    private fun buildClient(): OpenAIClient {
        return com.openai.client.okhttp.OpenAIOkHttpClient.builder()
            .baseUrl(baseUrl)
            .apiKey(authProvider.getAccessToken())
            .build()
    }

    override suspend fun generateContent(
        model: String,
        contents: List<Content>,
        config: GenerateContentConfig
    ): LlmResponse {
        val client = buildClient()
        val params = buildParams(model, contents, config)
        val completion = client.chat().completions().create(params)

        val choice = completion.choices().firstOrNull()
        val text = choice?.message()?.content()?.orElse(null)
        val usage = completion.usage().orElse(null)

        return LlmResponse(
            text = text,
            inputTokens = usage?.promptTokens() ?: 0,
            outputTokens = usage?.completionTokens() ?: 0,
            totalTokens = usage?.totalTokens() ?: 0,
            finishReason = choice?.finishReason()?.toString(),
        )
    }

    override fun generateContentStream(
        model: String,
        contents: List<Content>,
        config: GenerateContentConfig
    ): Flow<LlmResponse> = flow {
        val client = buildClient()
        val params = buildParams(model, contents, config)

        client.chat().completions().createStreaming(params).use { streamResponse ->
            val iterator = streamResponse.stream().iterator()
            while (iterator.hasNext()) {
                val chunk = iterator.next()
                val delta = chunk.choices().firstOrNull()?.delta()
                val text = delta?.content()?.orElse(null)
                val usage = chunk.usage().orElse(null)

                emit(
                    LlmResponse(
                        text = text,
                        inputTokens = usage?.promptTokens() ?: 0,
                        outputTokens = usage?.completionTokens() ?: 0,
                        totalTokens = usage?.totalTokens() ?: 0,
                        finishReason = null,
                    )
                )
            }
        }
    }

    private fun buildParams(
        model: String,
        contents: List<Content>,
        config: GenerateContentConfig
    ): ChatCompletionCreateParams {
        val qualifiedModel = if ('/' in model) model else "google/$model"
        val builder = ChatCompletionCreateParams.builder()
            .model(ChatModel.of(qualifiedModel))

        config.systemInstruction().ifPresent { sysContent ->
            val sysText = sysContent.parts().orElse(emptyList())
                .mapNotNull { it.text().orElse(null) }
                .joinToString("\n")
            if (sysText.isNotBlank()) {
                builder.addMessage(
                    ChatCompletionSystemMessageParam.builder()
                        .content(sysText)
                        .build()
                )
            }
        }

        for (content in contents) {
            val role = content.role().orElse("user")
            val parts = content.parts().orElse(emptyList())

            when (role) {
                "user" -> translateUserMessage(parts, builder)
                "model", "assistant" -> {
                    val text = parts.mapNotNull { it.text().orElse(null) }.joinToString("\n")
                    builder.addMessage(
                        ChatCompletionAssistantMessageParam.builder()
                            .content(text)
                            .build()
                    )
                }
                else -> {
                    logger.warn("Unknown content role '{}', treating as user", role)
                    translateUserMessage(parts, builder)
                }
            }
        }

        config.maxOutputTokens().ifPresent { builder.maxCompletionTokens(it.toLong()) }

        if (config.responseMimeType().orElse(null) == "application/json") {
            config.responseSchema().ifPresent { schema ->
                val jsonSchema = SchemaTranslator.toJsonSchema(schema)
                builder.responseFormat(buildResponseFormat(jsonSchema))
            }
            if (!config.responseSchema().isPresent) {
                builder.responseFormat(
                    ChatCompletionCreateParams.ResponseFormat.ofJsonObject(
                        com.openai.models.ResponseFormatJsonObject.builder().build()
                    )
                )
            }
        }

        return builder.build()
    }

    private fun translateUserMessage(parts: List<Part>, builder: ChatCompletionCreateParams.Builder) {
        val hasImages = parts.any { it.inlineData().isPresent }

        if (!hasImages) {
            val text = parts.mapNotNull { it.text().orElse(null) }.joinToString("\n")
            builder.addUserMessage(text)
            return
        }

        val contentParts = mutableListOf<ChatCompletionContentPart>()

        for (part in parts) {
            when {
                part.text().isPresent -> {
                    contentParts.add(
                        ChatCompletionContentPart.ofText(
                            ChatCompletionContentPartText.builder()
                                .text(part.text().get())
                                .build()
                        )
                    )
                }
                part.inlineData().isPresent -> {
                    val blob = part.inlineData().get()
                    val mimeType = blob.mimeType().orElse("image/png")
                    val base64Data = Base64.getEncoder().encodeToString(blob.data().orElse(byteArrayOf()))
                    val dataUri = "data:$mimeType;base64,$base64Data"

                    contentParts.add(
                        ChatCompletionContentPart.ofImageUrl(
                            ChatCompletionContentPartImage.builder()
                                .imageUrl(
                                    ChatCompletionContentPartImage.ImageUrl.builder()
                                        .url(dataUri)
                                        .build()
                                )
                                .build()
                        )
                    )
                }
            }
        }

        builder.addMessage(
            ChatCompletionUserMessageParam.builder()
                .content(ChatCompletionUserMessageParam.Content.ofArrayOfContentParts(contentParts))
                .build()
        )
    }

    private fun buildResponseFormat(jsonSchema: JsonObject): ChatCompletionCreateParams.ResponseFormat {
        val schemaJsonValue = toJsonValue(jsonSchema)
        return ChatCompletionCreateParams.ResponseFormat.ofJsonSchema(
            ResponseFormatJsonSchema.builder()
                .jsonSchema(
                    ResponseFormatJsonSchema.JsonSchema.builder()
                        .name("response_schema")
                        .schema(schemaJsonValue)
                        .strict(false)
                        .build()
                )
                .build()
        )
    }

    companion object {
        fun buildBaseUrl(projectId: String, location: String): String {
            return if (location == "global") {
                "https://aiplatform.googleapis.com/v1/projects/$projectId/locations/global/endpoints/openapi"
            } else {
                "https://$location-aiplatform.googleapis.com/v1/projects/$projectId/locations/$location/endpoints/openapi"
            }
        }

        private fun toJsonValue(element: JsonElement): JsonValue {
            return when (element) {
                is JsonPrimitive -> when {
                    element.isString -> JsonValue.from(element.content)
                    element.content == "true" -> JsonValue.from(true)
                    element.content == "false" -> JsonValue.from(false)
                    element.content == "null" -> JsonValue.from(null as String?)
                    element.content.contains('.') -> JsonValue.from(element.content.toDouble())
                    else -> JsonValue.from(element.content.toLongOrNull() ?: element.content)
                }
                is JsonObject -> JsonValue.from(element.entries.associate { (k, v) -> k to toJsonValue(v) })
                is JsonArray -> JsonValue.from(element.map { toJsonValue(it) })
                else -> JsonValue.from(element.toString())
            }
        }
    }
}
