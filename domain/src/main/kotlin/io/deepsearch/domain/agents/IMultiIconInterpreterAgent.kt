package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.constants.ImageMimeType

/**
 * Input for multi-icon interpretation.
 * Contains multiple icons to be interpreted in a single LLM call.
 */
data class MultiIconInterpreterInput(
    val icons: List<IconItem>
) : IAgent.IAgentInput {
    data class IconItem(
        val bytes: ByteArray,
        val mimeType: ImageMimeType,
    )
}

/**
 * Output for multi-icon interpretation.
 * Contains interpretations for all icons that were sent in the input.
 * The order of interpretations matches the order of icons in the input.
 */
data class MultiIconInterpreterOutput(
    val interpretations: List<IconInterpretation>
) : IAgent.IAgentOutput {
    data class IconInterpretation(
        val label: String?
    )
}

/**
 * Agent interface for interpreting multiple icons in a single LLM call.
 * This is more efficient than calling IconInterpreterAgent multiple times,
 * as it reduces the number of API calls and helps avoid rate limits.
 *
 * The output interpretations list will have the same size and order as the input icons list,
 * making it easy to match results back to the original icons by position.
 */
interface IMultiIconInterpreterAgent : IAgent<MultiIconInterpreterInput, MultiIconInterpreterOutput> {
    override suspend fun generate(input: MultiIconInterpreterInput): MultiIconInterpreterOutput
}

