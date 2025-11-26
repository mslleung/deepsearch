package io.deepsearch.domain.config

import io.deepsearch.domain.agents.*
import io.deepsearch.domain.agents.googlegenaiimpl.*
import io.deepsearch.domain.browser.BrowserRuntimePool
import io.deepsearch.domain.browser.IBrowserRuntimePool
import io.deepsearch.domain.services.IOcrImageTextExtractionService
import io.deepsearch.domain.ocr.ITesseractPool
import io.deepsearch.domain.services.OcrImageTextExtractionService
import io.deepsearch.domain.ocr.TesseractPoolImpl
import io.deepsearch.domain.services.ApiKeyCryptoService
import io.deepsearch.domain.services.CssSelectorConstructionService
import io.deepsearch.domain.services.GeminiTextEmbeddingServiceImpl
import io.deepsearch.domain.services.IApiKeyCryptoService
import io.deepsearch.domain.services.ICssSelectorConstructionService
import io.deepsearch.domain.services.IJwtService
import io.deepsearch.domain.services.INormalizeUrlService
import io.deepsearch.domain.services.ISerperService
import io.deepsearch.domain.services.ITextEmbeddingService
import io.deepsearch.domain.services.JwtService
import io.deepsearch.domain.services.NormalizeUrlService
import io.deepsearch.domain.services.SerperService
import org.koin.core.module.dsl.createdAtStart
import org.koin.core.module.dsl.scopedOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.module.requestScope

val domainModule = module {
    singleOf(::ApplicationCoroutineScope) bind IApplicationCoroutineScope::class
    singleOf(::DefaultDispatcherProvider) bind IDispatcherProvider::class

    // OCR services
    singleOf(::BrowserRuntimePool) { createdAtStart() } bind IBrowserRuntimePool::class
    singleOf(::TesseractPoolImpl) { createdAtStart() } bind ITesseractPool::class

    // used by periodic index service
    singleOf(::NormalizeUrlService) bind INormalizeUrlService::class

    requestScope {
        // domain agents (request scoped) - now using GenAI SDK implementations
        scopedOf(::AggregateSearchResultsAgentGenAiImpl) bind IAggregateSearchResultsAgent::class
        scopedOf(::AnswerReviewerAgentGenAiImpl) bind IAnswerReviewerAgent::class
        scopedOf(::AnswerSynthesisAgentGenAiImpl) bind IAnswerSynthesisAgent::class
        scopedOf(::BlinkTestAgentGenAiImpl) bind IBlinkTestAgent::class
        scopedOf(::DirectAnswerAgentGenAiImpl) bind IDirectAnswerAgent::class
        scopedOf(::GenerateAnswerAgentGenAiImpl) bind IGenerateAnswerAgent::class
        scopedOf(::GoogleCombinedSearchAgentGenAiImpl) bind IGoogleCombinedSearchAgent::class
        scopedOf(::GoogleSearchLinkDiscoveryAgentGenAiImpl) bind IGoogleSearchLinkDiscoveryAgent::class
        scopedOf(::GoogleTextSearchAgentGenAiImpl) bind IGoogleTextSearchAgent::class
        scopedOf(::GoogleUrlContextSearchAgentGenAiImpl) bind IGoogleUrlContextSearchAgent::class
        scopedOf(::IconInterpreterAgentGenAiImpl) bind IIconInterpreterAgent::class
        scopedOf(::LinkRelevanceAnalysisAgentGenAiImpl) bind ILinkRelevanceAnalysisAgent::class
        scopedOf(::MarkdownConversionAgentGenAiImpl) bind IMarkdownConversionAgent::class
        scopedOf(::MultiIconInterpreterAgentGenAiImpl) bind IMultiIconInterpreterAgent::class
        scopedOf(::MultiImageTextExtractionAgentGenAiImpl) bind IMultiImageTextExtractionAgent::class
        scopedOf(::PdfToMarkdownAgentGenAiImpl) bind IPdfToMarkdownAgent::class
        scopedOf(::PopupContainerIdentificationAgentGenAiImpl) bind IPopupContainerIdentificationAgent::class
        scopedOf(::QueryBreakdownAgentGenAiImpl) bind IQueryBreakdownAgent::class
        scopedOf(::QueryExpansionAgentGenAiImpl) bind IQueryExpansionAgent::class
        scopedOf(::SemanticIdentificationAgentGenAiImpl) bind ISemanticIdentificationAgent::class
        scopedOf(::StreamingAnswerAgentGenAiImpl) bind IStreamingAnswerAgent::class
        scopedOf(::StreamingSourceShortlistAgentGenAiImpl) bind IStreamingSourceShortlistAgent::class
        scopedOf(::TableIdentificationAgentGenAiImpl) bind ITableIdentificationAgent::class
        scopedOf(::TableInterpretationAgentGenAiImpl) bind ITableInterpretationAgent::class

        // domain services
        scopedOf(::ApiKeyCryptoService) bind IApiKeyCryptoService::class
        scopedOf(::CssSelectorConstructionService) bind ICssSelectorConstructionService::class
        scopedOf(::JwtService) bind IJwtService::class
        scopedOf(::OcrImageTextExtractionService) bind IOcrImageTextExtractionService::class
        scopedOf(::SerperService) bind ISerperService::class
        scopedOf(::GeminiTextEmbeddingServiceImpl) bind ITextEmbeddingService::class
    }
}