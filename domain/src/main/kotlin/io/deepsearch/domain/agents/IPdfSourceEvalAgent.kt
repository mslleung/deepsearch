package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.EvaluatedSource
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.models.valueobjects.UrlContentResult

/**
 * Input for PDF source evaluation agent.
 * Provides a single PDF preview source to evaluate and extract facts from.
 * 
 * The agent internally appends `site:<domain>` to the query for better context.
 * Domain is extracted from the source URL.
 * 
 * @property pdfSource The PDF preview source to evaluate (text extracted via PDFTextStripper)
 * @property expandedQuery Context-aware expanded query to evaluate against
 * @property fulfillmentRequirements List of requirements that must be satisfied
 */
data class PdfSourceEvalInput(
    val pdfSource: UrlContentResult.PdfPreview,
    val expandedQuery: String,
    val fulfillmentRequirements: List<String> = emptyList()
) : IAgent.IAgentInput

/**
 * Output from PDF source evaluation agent.
 * Contains the evaluated source with extracted facts, or null if no relevant facts found.
 * 
 * Facts where isGarbled=true or isFromTable=true are filtered out before returning,
 * since garbled text (OCR artifacts, encoding issues) and table data in raw PDF
 * text extraction may be inaccurate.
 * 
 * @property evaluatedSource The evaluated source with extracted facts, or null if not relevant
 * @property tokenUsage Token usage metrics for this evaluation
 */
data class PdfSourceEvalOutput(
    val evaluatedSource: EvaluatedSource?,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

/**
 * Agent that evaluates a single PDF preview source and extracts facts.
 * 
 * For the source, the agent:
 * - Determines the intention (purpose) of the PDF document
 * - Assesses relevance to the query
 * - Extracts facts relevant to the query
 * - Marks whether each fact is garbled (OCR artifacts, encoding issues)
 * - Marks whether each fact comes from a table structure
 * 
 * Facts where isGarbled=true or isFromTable=true are filtered out before returning,
 * as such data in raw PDF text extraction may be inaccurate.
 * 
 * This is a stateless agent that processes one source at a time, enabling parallel processing.
 * Used in the preview path for early exit with conservative fact extraction.
 */
interface IPdfSourceEvalAgent : IAgent<PdfSourceEvalInput, PdfSourceEvalOutput> {
    override suspend fun generate(input: PdfSourceEvalInput): PdfSourceEvalOutput
}
