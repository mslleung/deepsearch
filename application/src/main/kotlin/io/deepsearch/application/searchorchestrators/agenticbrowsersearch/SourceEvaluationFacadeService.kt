package io.deepsearch.application.searchorchestrators.agenticbrowsersearch

import io.deepsearch.application.services.IHtmlSourceEvalService
import io.deepsearch.application.services.ILlmTokenUsageService
import io.deepsearch.application.services.IPdfSourceEvalService
import io.deepsearch.domain.agents.HtmlSourceEvalInput
import io.deepsearch.domain.agents.IMarkdownSourceEvalAgent
import io.deepsearch.domain.agents.MarkdownSourceEvalInput
import io.deepsearch.domain.agents.PdfSourceEvalInput
import io.deepsearch.domain.models.valueobjects.EvaluatedSource
import io.deepsearch.domain.models.valueobjects.MarkdownSource
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.UrlContentResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Facade service for evaluating sources (HTML, PDF, Markdown) for relevance to a query.
 * Consolidates all source evaluation logic into a single service.
 */
interface ISourceEvaluationFacadeService {
    /**
     * Evaluate an HTML preview source for relevance.
     * @return EvaluatedSource if relevant, null otherwise
     */
    suspend fun evaluateHtmlSource(
        sessionId: QuerySessionId,
        htmlSource: UrlContentResult.HtmlPreview,
        expandedQuery: String,
        fulfillmentRequirements: List<String> = emptyList()
    ): EvaluatedSource?

    /**
     * Evaluate a PDF preview source for relevance.
     * @return EvaluatedSource if relevant, null otherwise
     */
    suspend fun evaluatePdfSource(
        sessionId: QuerySessionId,
        pdfSource: UrlContentResult.PdfPreview,
        expandedQuery: String,
        fulfillmentRequirements: List<String> = emptyList()
    ): EvaluatedSource?

    /**
     * Evaluate a markdown source for relevance.
     * @return EvaluatedSource if relevant, null otherwise
     */
    suspend fun evaluateMarkdownSource(
        sessionId: QuerySessionId,
        markdownSource: MarkdownSource,
        expandedQuery: String,
        fulfillmentRequirements: List<String> = emptyList()
    ): EvaluatedSource?
}

class SourceEvaluationFacadeService(
    private val htmlSourceEvalService: IHtmlSourceEvalService,
    private val pdfSourceEvalService: IPdfSourceEvalService,
    private val markdownSourceEvalAgent: IMarkdownSourceEvalAgent,
    private val tokenUsageService: ILlmTokenUsageService
) : ISourceEvaluationFacadeService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun evaluateHtmlSource(
        sessionId: QuerySessionId,
        htmlSource: UrlContentResult.HtmlPreview,
        expandedQuery: String,
        fulfillmentRequirements: List<String>
    ): EvaluatedSource? {
        val evalOutput = htmlSourceEvalService.evaluate(
            HtmlSourceEvalInput(
                htmlSource = htmlSource,
                expandedQuery = expandedQuery,
                fulfillmentRequirements = fulfillmentRequirements
            ),
            sessionId
        )

        // Cooperative cancellation check
        currentCoroutineContext().ensureActive()

        return evalOutput.evaluatedSource?.also { source ->
            logger.debug(
                "[{}] HTML source evaluation: url={}, {} facts",
                sessionId.value, htmlSource.url, source.relevantFacts.size
            )
        }
    }

    override suspend fun evaluatePdfSource(
        sessionId: QuerySessionId,
        pdfSource: UrlContentResult.PdfPreview,
        expandedQuery: String,
        fulfillmentRequirements: List<String>
    ): EvaluatedSource? {
        val evalOutput = pdfSourceEvalService.evaluate(
            PdfSourceEvalInput(
                pdfSource = pdfSource,
                expandedQuery = expandedQuery,
                fulfillmentRequirements = fulfillmentRequirements
            ),
            sessionId
        )

        // Cooperative cancellation check
        currentCoroutineContext().ensureActive()

        return evalOutput.evaluatedSource?.also { source ->
            logger.debug(
                "[{}] PDF source evaluation: url={}, {} facts",
                sessionId.value, pdfSource.url, source.relevantFacts.size
            )
        }
    }

    override suspend fun evaluateMarkdownSource(
        sessionId: QuerySessionId,
        markdownSource: MarkdownSource,
        expandedQuery: String,
        fulfillmentRequirements: List<String>
    ): EvaluatedSource? {
        val output = markdownSourceEvalAgent.generate(
            MarkdownSourceEvalInput(
                markdownSource = markdownSource,
                expandedQuery = expandedQuery,
                fulfillmentRequirements = fulfillmentRequirements
            )
        )

        // Cooperative cancellation check
        currentCoroutineContext().ensureActive()

        tokenUsageService.recordTokenUsage(
            sessionId, "MarkdownSourceEvalAgent",
            output.tokenUsage.modelName, output.tokenUsage.promptTokens,
            output.tokenUsage.outputTokens, output.tokenUsage.totalTokens
        )

        return output.evaluatedSource
    }
}
