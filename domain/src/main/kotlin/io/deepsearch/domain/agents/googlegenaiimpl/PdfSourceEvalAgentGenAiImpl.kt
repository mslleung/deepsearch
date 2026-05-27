package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel
import io.deepsearch.domain.agents.PdfSourceEvalInput
import io.deepsearch.domain.agents.PdfSourceEvalOutput
import io.deepsearch.domain.agents.IPdfSourceEvalAgent
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.EvaluatedSource
import io.deepsearch.domain.models.valueobjects.RelevantFact
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * PDF Source Evaluation agent that evaluates a single PDF preview source and extracts facts.
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
class PdfSourceEvalAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IPdfSourceEvalAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val relevantFactSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("A fact extracted from the PDF source with metadata")
        .properties(
            mapOf(
                "fact" to Schema.builder()
                    .type("STRING")
                    .description("The extracted fact as a complete, standalone statement")
                    .build(),
                "isGarbled" to Schema.builder()
                    .type("BOOLEAN")
                    .description("True if the fact contains garbled text: OCR artifacts, encoding issues (mojibake), random character sequences, or corrupted text")
                    .build(),
                "isFromTable" to Schema.builder()
                    .type("BOOLEAN")
                    .description("True if the fact appears to come from a table structure (columns may be merged incorrectly in raw text extraction)")
                    .build()
            )
        )
        .required(listOf("fact", "isGarbled", "isFromTable"))
        .build()

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Evaluated PDF source with intention and extracted facts")
        .properties(
            mapOf(
                "intention" to Schema.builder().type("STRING")
                    .description("Describes the purpose of the PDF document (e.g., 'Official product whitepaper', 'Research paper on machine learning', 'Company annual report 2023')")
                    .build(),
                "contentDate" to Schema.builder().type("STRING")
                    .description("Date extracted from content (null/empty if no date found)")
                    .nullable(true)
                    .build(),
                "relevantFacts" to Schema.builder()
                    .type("ARRAY")
                    .items(relevantFactSchema)
                    .description("List of facts extracted from the PDF that are relevant to the query. Empty if document is not relevant.")
                    .build()
            )
        )
        .required(listOf("intention", "relevantFacts"))
        .build()

    private val systemInstruction = """
        Given extracted text from a PDF document. Your task is to interpret and extract relevant facts from the text
        
        Input: 
        - Current date
        - User query (with site: context)
        - Raw text extracted from PDF - may have formatting issues
        
        Instructions:
        1. **Intention** (intention):
           Describe the purpose of the PDF document in a concise sentence. Consider:
           - What type of document is this? (whitepaper, research paper, manual, report, brochure, etc.)
           - Is it official content or third-party content?
           - Is it current content or a dated document?
           Examples:
           - "Official product whitepaper describing enterprise security features"
           - "Academic research paper on natural language processing from 2023"
           - "Company quarterly financial report Q3 2024"
           - "User manual for software version 2.5"
        
        2. **Content Date** (contentDate):
           - Extract the publication or update date if available
           - Look for dates in headers, footers, metadata text, or content
           - If no date is found, leave it null/empty
        
        3. **Extract Relevant Facts** (relevantFacts):
           - Extract facts that are directly relevant to answering the query and address the requirements
           - Facts should be very detailed, providing as much information or supporting evidence if possible
           - All relevant facts must be extracted with no omission
           - Each fact should be a complete, standalone statement
           - If the document is not relevant, return an empty array
           
           For each fact, you MUST assess:
           - `isGarbled`: true if the text shows signs of:
             * OCR artifacts (random symbols like ####, ????, garbled characters)
             * Encoding issues (mojibake like â€™ instead of apostrophe, Ã© instead of é)
             * Corrupted text (incomplete words, nonsensical character sequences)
             * Layout corruption (merged columns producing unreadable text)
           - `isFromTable`: true if the fact appears to come from tabular data
             * Table columns often merge incorrectly in raw PDF text extraction
             * Data that looks like "Column1 Column2 Value1 Value2" patterns
        
        Output Format:
        {
          "intention": "Official product whitepaper describing enterprise features",
          "contentDate": "2024-03-15",
          "relevantFacts": [
            {"fact": "The enterprise plan includes 99.9% SLA guarantee", "isGarbled": false, "isFromTable": false},
            {"fact": "Pricing starts at ${'$'}499/month for teams", "isGarbled": false, "isFromTable": true},
            {"fact": "Thâ€™e systÃ©m suppÃ´rts", "isGarbled": true, "isFromTable": false}
          ]
        }
    """.trimIndent()

    @Serializable
    private data class LlmRelevantFact(
        val fact: String,
        val isGarbled: Boolean,
        val isFromTable: Boolean
    )

    @Serializable
    private data class EvalResponse(
        val intention: String,
        val contentDate: String? = null,
        val relevantFacts: List<LlmRelevantFact> = emptyList()
    )

    override suspend fun generate(input: PdfSourceEvalInput): PdfSourceEvalOutput {
        val pdfSource = input.pdfSource
        val queryWithSite = "${input.expandedQuery} site:${extractDomain(pdfSource.url)}"
        
        logger.debug(
            "Evaluating PDF source for query: '{}', url: {}, pageCount: {}",
            queryWithSite,
            pdfSource.url,
            pdfSource.pageCount
        )

        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val userPrompt = buildUserPrompt(input)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<EvalResponse>(this@PdfSourceEvalAgentGenAiImpl::class.simpleName!!) {
                val result = client.models.generateContent(
                    modelId,
                    userPrompt,
                    GenerateContentConfig.builder()
                        .temperature(1.0F)
                        .responseSchema(outputSchema)
                        .responseMimeType("application/json")
                        .thinkingConfig(
                            ThinkingConfig.builder()
                                .thinkingLevel(ThinkingLevel.Known.MINIMAL)
                                .build()
                        )
                        .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
                        .build()
                )

                result.checkFinishReason()

                // Extract token usage
                result.usageMetadata().ifPresent { metadata ->
                    tokenUsage = TokenUsageMetrics(
                        modelName = modelId,
                        promptTokens = metadata.promptTokenCount().orElse(0),
                        outputTokens = metadata.candidatesTokenCount().orElse(0),
                        totalTokens = metadata.totalTokenCount().orElse(0)
                    )
                }

                result.text() ?: throw RuntimeException("No text response from model")
            }
        }

        logger.debug(
            "[{}] response for {}: intention='{}', facts={}",
            PdfSourceEvalAgentGenAiImpl::class.simpleName,
            pdfSource.url,
            response.intention,
            response.relevantFacts.size
        )

        // Filter out facts where isGarbled=true or isFromTable=true
        // (garbled text and table data in raw PDF extraction are unreliable)
        val filteredFacts = response.relevantFacts
            .filter { !it.isGarbled && !it.isFromTable }
            .map { llmFact ->
                RelevantFact(fact = llmFact.fact)
            }

        val garbledCount = response.relevantFacts.count { it.isGarbled }
        val tableCount = response.relevantFacts.count { it.isFromTable }
        
        if (garbledCount > 0 || tableCount > 0) {
            logger.debug(
                "Source {} filtered: {} garbled, {} from tables, {} remaining",
                pdfSource.url, garbledCount, tableCount, filteredFacts.size
            )
        }

        // If no facts remain after filtering, return null
        if (filteredFacts.isEmpty()) {
            logger.debug("Source {} has no facts after filtering, returning null", pdfSource.url)
            return PdfSourceEvalOutput(
                evaluatedSource = null,
                tokenUsage = tokenUsage
            )
        }

        val evaluatedSource = EvaluatedSource(
            url = pdfSource.url,
            title = pdfSource.title,
            description = pdfSource.description,
            relevantFacts = filteredFacts,
            contentDate = response.contentDate,
            intention = response.intention,
            relevantImageIds = emptyList()
        )

        logger.debug(
            "PDF source evaluation complete for {}: {} facts (after filtering garbled/table data)",
            pdfSource.url,
            filteredFacts.size
        )

        return PdfSourceEvalOutput(
            evaluatedSource = evaluatedSource,
            tokenUsage = tokenUsage
        )
    }

    /**
     * Extracts the domain from a URL string.
     */
    private fun extractDomain(url: String): String {
        return try {
            java.net.URI(url).host?.lowercase() ?: url
        } catch (e: Exception) {
            url
        }
    }

    private fun buildUserPrompt(input: PdfSourceEvalInput): String {
        val queryWithSite = "${input.expandedQuery} site:${extractDomain(input.pdfSource.url)}"
        val source = input.pdfSource
        return buildString {
            // Include current date for temporal context
            appendLine("# Current Date")
            appendLine(java.time.LocalDate.now().toString())
            appendLine()

            appendLine("# Query")
            appendLine(queryWithSite)
            appendLine()

            // Include fulfillment requirements if available
            if (input.fulfillmentRequirements.isNotEmpty()) {
                appendLine("# Fulfillment Requirements")
                input.fulfillmentRequirements.forEachIndexed { index, req ->
                    appendLine("${index + 1}. $req")
                }
                appendLine()
            }

            appendLine("# PDF Source to Evaluate")
            appendLine("URL: ${source.url}")
            if (source.title != null) {
                appendLine("Title: ${source.title}")
            }
            appendLine("Page Count: ${source.pageCount}")
            appendLine()
            appendLine("## Extracted Text Content")
            appendLine("```")
            appendLine(source.extractedText)
            appendLine("```")
        }
    }
}
