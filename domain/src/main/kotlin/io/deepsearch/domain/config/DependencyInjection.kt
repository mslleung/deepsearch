package io.deepsearch.domain.config

import io.deepsearch.domain.agents.*
import io.deepsearch.domain.agents.googlegenaiimpl.*
import io.deepsearch.domain.browser.BrowserRuntimePool
import io.deepsearch.domain.browser.IBrowserRuntimePool
import io.deepsearch.domain.services.IOcrImageTextExtractionService
import io.deepsearch.domain.ocr.ITesseractPool
import io.deepsearch.domain.services.OcrImageTextExtractionService
import io.deepsearch.domain.ocr.TesseractPoolImpl
import io.deepsearch.domain.ratelimit.AdaptiveRateLimiter
import io.deepsearch.domain.ratelimit.IAdaptiveRateLimiter
import io.deepsearch.domain.services.ApiKeyCryptoService
import io.deepsearch.domain.services.CssSelectorConstructionService
import io.deepsearch.domain.services.GeminiFileSearchService
import io.deepsearch.domain.services.GeminiTextEmbeddingServiceImpl
import io.deepsearch.domain.services.IApiKeyCryptoService
import io.deepsearch.domain.services.ICssSelectorConstructionService
import io.deepsearch.domain.services.IGeminiFileSearchService
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

    // Singleton domain services (used by singleton application services)
    singleOf(::NormalizeUrlService) bind INormalizeUrlService::class
    singleOf(::SerperService) bind ISerperService::class
    singleOf(::GeminiTextEmbeddingServiceImpl) bind ITextEmbeddingService::class
    singleOf(::OcrImageTextExtractionService) bind IOcrImageTextExtractionService::class
    singleOf(::CssSelectorConstructionService) bind ICssSelectorConstructionService::class
    singleOf(::GeminiFileSearchService) bind IGeminiFileSearchService::class
    
    // Adaptive rate limiter (singleton to maintain state across requests per domain)
    singleOf(::AdaptiveRateLimiter) bind IAdaptiveRateLimiter::class

    // Singleton domain agents (used by singleton application services)
    singleOf(::FileSearchQueryAgentGenAiImpl) bind IFileSearchQueryAgent::class
    singleOf(::GoogleSearchLinkDiscoveryAgentGenAiImpl) bind IGoogleSearchLinkDiscoveryAgent::class
    singleOf(::LinkRelevanceAnalysisAgentGenAiImpl) bind ILinkRelevanceAnalysisAgent::class
    singleOf(::MultiIconInterpreterAgentGenAiImpl) bind IMultiIconInterpreterAgent::class
    singleOf(::ImageClassificationAgentGenAiImpl) bind IImageClassificationAgent::class
    singleOf(::TableExtractionAgentGenAiImpl) bind ITableExtractionAgent::class
    singleOf(::TextLinkDiscoveryAgentGenAiImpl) bind ITextLinkDiscoveryAgent::class
    singleOf(::PopupContainerIdentificationAgentGenAiImpl) bind IPopupContainerIdentificationAgent::class
    singleOf(::SemanticIdentificationAgentGenAiImpl) bind ISemanticIdentificationAgent::class
    singleOf(::TableIdentificationAgentGenAiImpl) bind ITableIdentificationAgent::class
    singleOf(::TableInterpretationAgentGenAiImpl) bind ITableInterpretationAgent::class

    // Request-scoped domain agents (used by request-scoped application services)
    requestScope {
        scopedOf(::AnswerReviewerAgentGenAiImpl) bind IAnswerReviewerAgent::class
        scopedOf(::AnswerSynthesisAgentGenAiImpl) bind IAnswerSynthesisAgent::class
        scopedOf(::BlinkTestAgentGenAiImpl) bind IBlinkTestAgent::class
        scopedOf(::DirectAnswerAgentGenAiImpl) bind IDirectAnswerAgent::class
        scopedOf(::GenerateAnswerAgentGenAiImpl) bind IGenerateAnswerAgent::class
        scopedOf(::GoogleCombinedSearchAgentGenAiImpl) bind IGoogleCombinedSearchAgent::class
        scopedOf(::GoogleTextSearchAgentGenAiImpl) bind IGoogleTextSearchAgent::class
        scopedOf(::GoogleUrlContextSearchAgentGenAiImpl) bind IGoogleUrlContextSearchAgent::class
        scopedOf(::IconInterpreterAgentGenAiImpl) bind IIconInterpreterAgent::class
        scopedOf(::MarkdownConversionAgentGenAiImpl) bind IMarkdownConversionAgent::class
        scopedOf(::QueryBreakdownAgentGenAiImpl) bind IQueryBreakdownAgent::class
        scopedOf(::QueryExpansionAgentGenAiImpl) bind IQueryExpansionAgent::class
        scopedOf(::SerpQueryOptimizationAgentGenAiImpl) bind ISerpQueryOptimizationAgent::class
        scopedOf(::StreamingAnswerAgentGenAiImpl) bind IStreamingAnswerAgent::class
        scopedOf(::StreamingSourceShortlistAgentGenAiImpl) bind IStreamingSourceShortlistAgent::class

        // Request-scoped domain services (user/auth related)
        scopedOf(::ApiKeyCryptoService) bind IApiKeyCryptoService::class
        scopedOf(::JwtService) bind IJwtService::class
    }
}