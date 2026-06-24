package io.deepsearch.domain.agents.infra

/**
 * Enum of supported Gemini model IDs with their pricing information.
 * 
 * Pricing is in USD per 1 million tokens.
 * Source: https://ai.google.dev/gemini-api/docs/pricing
 * 
 * Note: Batch API provides 50% discount on these prices.
 * The discount should be applied at usage time, not in the pricing constants.
 * 
 * @property modelId The model identifier string used in API calls
 * @property inputPricePerMillion Price per 1M input tokens in USD
 * @property outputPricePerMillion Price per 1M output tokens in USD
 */
enum class ModelIds(
    val modelId: String,
    val inputPricePerMillion: Double,
    val outputPricePerMillion: Double
) {
    // Gemini 3.5 models
    GEMINI_3_5_FLASH("gemini-3.5-flash", 1.50, 9.00),

    // Gemini 3.1 models
    GEMINI_3_1_FLASH_LITE("gemini-3.1-flash-lite", 0.25, 1.50),

    // Gemini 3 models
    GEMINI_3_PRO_PREVIEW("gemini-3-pro-preview", 2.00, 12.00),
    GEMINI_3_FLASH_PREVIEW("gemini-3-flash-preview", 0.50, 3.00),

    // Gemini 2.5 Pro models
    GEMINI_2_5_PRO("gemini-2.5-pro", 1.25, 10.00),

    // Gemini 2.5 Flash models
    GEMINI_2_5_FLASH("gemini-2.5-flash", 0.30, 2.50),
    GEMINI_2_5_FLASH_PREVIEW("gemini-2.5-flash-preview-09-2025", 0.30, 2.50),

    // Gemini 2.5 Flash Lite models
    GEMINI_2_5_FLASH_LITE("gemini-2.5-flash-lite", 0.10, 0.40),
    GEMINI_2_5_FLASH_LITE_PREVIEW("gemini-2.5-flash-lite-preview-09-2025", 0.10, 0.40),

    // Gemini 2.0 Flash models
    GEMINI_2_0_FLASH("gemini-2.0-flash", 0.10, 0.40),
    GEMINI_2_0_FLASH_LITE("gemini-2.0-flash-lite", 0.075, 0.30),

    // Embedding models
    GEMINI_EMBEDDING_001("gemini-embedding-001", 0.15, 0.0),
    GEMINI_EMBEDDING_2_PREVIEW("gemini-embedding-2-preview", 0.20, 0.0);

    companion object {
        private val byModelId: Map<String, ModelIds> = entries.associateBy { it.modelId }

        /**
         * Find a ModelIds enum by its model ID string.
         * @param modelId The model identifier string
         * @return The matching ModelIds enum, or null if not found
         */
        fun fromModelId(modelId: String): ModelIds? = byModelId[modelId]

        /**
         * Get pricing for a model ID string.
         * 
         * @param modelId The model identifier string
         * @return Pair of (inputPricePerMillion, outputPricePerMillion) in USD
         * @throws IllegalArgumentException if model ID is not found
         */
        fun getPricing(modelId: String): Pair<Double, Double> {
            val model = fromModelId(modelId)
                ?: throw IllegalArgumentException("Unknown model ID: $modelId. Add it to ModelIds enum.")
            return Pair(model.inputPricePerMillion, model.outputPricePerMillion)
        }
    }
}
