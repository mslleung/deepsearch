package io.deepsearch.domain.config

import io.deepsearch.domain.agents.*
import io.deepsearch.domain.agents.googlegenaiimpl.*
import io.deepsearch.domain.agents.infra.llm.GenAiLlmClient
import io.deepsearch.domain.agents.infra.llm.ILlmClient
import io.deepsearch.domain.agents.infra.llm.OpenAiLlmClient
import io.deepsearch.domain.agents.infra.llm.RoutingLlmClient
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.browser.remote.RemoteBrowserPool
import io.deepsearch.domain.services.IOcrImageTextExtractionService
import io.deepsearch.domain.ocr.ITesseractPool
import io.deepsearch.domain.services.OcrImageTextExtractionService
import io.deepsearch.domain.ocr.TesseractPoolImpl
import io.deepsearch.domain.ratelimit.AdaptiveRateLimiter
import io.deepsearch.domain.ratelimit.IAdaptiveRateLimiter
import io.deepsearch.domain.services.ApiKeyCryptoService
import io.deepsearch.domain.services.BoundingBoxDerivationService
import io.deepsearch.domain.services.CssSelectorConstructionService
import io.deepsearch.domain.services.ISemanticListConverter
import io.deepsearch.domain.services.ISemanticTableConverter
import io.deepsearch.domain.services.SemanticListConverter
import io.deepsearch.domain.services.SemanticTableConverter
import io.deepsearch.domain.services.GeminiFileSearchService
import io.deepsearch.domain.services.GeminiTextEmbeddingServiceImpl
import io.deepsearch.domain.services.IApiKeyCryptoService
import io.deepsearch.domain.services.IBoundingBoxDerivationService
import io.deepsearch.domain.services.ICssSelectorConstructionService
import io.deepsearch.domain.services.IGeminiFileSearchService
import io.deepsearch.domain.services.IHtmlToMarkdownService
import io.deepsearch.domain.services.IImageDimensionService
import io.deepsearch.domain.services.IJsoupDomService
import io.deepsearch.domain.services.ITableGridDetectorService
import io.deepsearch.domain.services.IRecursiveTableDiscoveryService
import io.deepsearch.domain.detection.TableGridDetectorService
import io.deepsearch.domain.detection.RecursiveTableDiscoveryService
import io.deepsearch.domain.services.HtmlToMarkdownService
import io.deepsearch.domain.services.ImageDimensionService
import io.deepsearch.domain.services.IJwtService
import io.deepsearch.domain.services.INormalizeUrlService
import io.deepsearch.domain.services.ISerperService
import io.deepsearch.domain.services.ITextEmbeddingService
import io.deepsearch.domain.services.JsoupDomService
import io.deepsearch.domain.services.JwtService
import io.deepsearch.domain.services.NormalizeUrlService
import io.deepsearch.domain.services.DomDiffService
import io.deepsearch.domain.services.IDomDiffService
import io.deepsearch.domain.services.IImageProcessingService
import io.deepsearch.domain.services.ImageProcessingService
import io.deepsearch.domain.services.SerperService
import io.deepsearch.domain.services.GeminiBatchServiceImpl
import io.deepsearch.domain.services.IGeminiBatchService
import io.deepsearch.domain.http.IProxyAwareHttpClientFactory
import io.deepsearch.domain.http.ProxyAwareHttpClientFactory
import io.deepsearch.domain.proxy.FreeProxyPool
import io.deepsearch.domain.proxy.FreeProxySyncService
import io.deepsearch.domain.proxy.IFreeProxyProvider
import io.deepsearch.domain.proxy.IProxyTestService
import io.deepsearch.domain.proxy.FreeProxyProvider
import io.deepsearch.domain.proxy.ProxyTestService
import org.koin.core.module.dsl.createdAtStart
import org.koin.core.module.dsl.scopedOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.module.requestScope

val domainModule = module {
    singleOf(::ApplicationCoroutineScope) bind IApplicationCoroutineScope::class
    singleOf(::DefaultDispatcherProvider) bind IDispatcherProvider::class

    // LLM client routing: GenAiLlmClient + OpenAiLlmClient are injected by the presentation layer.
    // RoutingLlmClient selects the correct backend based on ModelIds.backend.
    singleOf(::RoutingLlmClient) bind ILlmClient::class

    // Browser pool - connects to remote deepsearch-browser service
    // DeepsearchBrowserConfig is provided by the presentation layer
    singleOf(::RemoteBrowserPool) bind IBrowserPool::class

    // OCR pool
    singleOf(::TesseractPoolImpl) { createdAtStart() } bind ITesseractPool::class

    // Singleton domain services (used by singleton application services)
    singleOf(::NormalizeUrlService) bind INormalizeUrlService::class
    singleOf(::SerperService) bind ISerperService::class
    singleOf(::GeminiTextEmbeddingServiceImpl) bind ITextEmbeddingService::class
    singleOf(::OcrImageTextExtractionService) bind IOcrImageTextExtractionService::class
    singleOf(::CssSelectorConstructionService) bind ICssSelectorConstructionService::class
    singleOf(::BoundingBoxDerivationService) bind IBoundingBoxDerivationService::class
    singleOf(::JsoupDomService) bind IJsoupDomService::class
    singleOf(::HtmlToMarkdownService) bind IHtmlToMarkdownService::class
    singleOf(::GeminiFileSearchService) bind IGeminiFileSearchService::class
    singleOf(::ImageDimensionService) bind IImageDimensionService::class
    singleOf(::TableGridDetectorService) bind ITableGridDetectorService::class
    singleOf(::RecursiveTableDiscoveryService) bind IRecursiveTableDiscoveryService::class
    singleOf(::SemanticTableConverter) bind ISemanticTableConverter::class
    singleOf(::SemanticListConverter) bind ISemanticListConverter::class
    singleOf(::ImageProcessingService) bind IImageProcessingService::class
    singleOf(::DomDiffService) bind IDomDiffService::class

    // Gemini Batch API service for cost-effective large-scale processing
    // Uses inline requests for batches under 20MB, 50% cost savings
    singleOf(::GeminiBatchServiceImpl) bind IGeminiBatchService::class
    
    // Adaptive rate limiter (singleton to maintain state across requests per domain)
    singleOf(::AdaptiveRateLimiter) bind IAdaptiveRateLimiter::class
    
    // Proxy-aware HTTP client factory for making proxied requests
    singleOf(::ProxyAwareHttpClientFactory) bind IProxyAwareHttpClientFactory::class
    
    // Proxy test service for validating proxy connections
    singleOf(::ProxyTestService) bind IProxyTestService::class
    
    // Free proxy sync service and pool for managing rotating free proxies
    singleOf(::FreeProxySyncService)
    singleOf(::FreeProxyPool)
    singleOf(::FreeProxyProvider) bind IFreeProxyProvider::class

    // Singleton domain agents (used by singleton application services)
    singleOf(::FileSearchQueryAgentGenAiImpl) bind IFileSearchQueryAgent::class
    singleOf(::GoogleSearchLinkDiscoveryAgentGenAiImpl) bind IGoogleSearchLinkDiscoveryAgent::class
    singleOf(::LinkRelevanceAnalysisAgentGenAiImpl) bind ILinkRelevanceAnalysisAgent::class
    singleOf(::MultiIconInterpreterAgentGenAiImpl) bind IMultiIconInterpreterAgent::class
    singleOf(::ImageClassificationAgentGenAiImpl) bind IImageClassificationAgent::class
    singleOf(::ImageDescriptionAgentGenAiImpl) bind IImageDescriptionAgent::class
    singleOf(::TableExtractionAgentGenAiImpl) bind ITableExtractionAgent::class
    singleOf(::AgenticTableConversionAgentGenAiImpl) bind IAgenticTableConversionAgent::class
    singleOf(::TextLinkDiscoveryAgentGenAiImpl) bind ITextLinkDiscoveryAgent::class
    singleOf(::PopupContainerIdentificationAgentGenAiImpl) bind IPopupContainerIdentificationAgent::class
    singleOf(::SemanticIdentificationAgentGenAiImpl) bind ISemanticIdentificationAgent::class
    singleOf(::TableInterpretationAgentGenAiImpl) bind ITableInterpretationAgent::class
    singleOf(::VisualIdentificationAgentGenAiImpl) bind IVisualIdentificationAgent::class
    singleOf(::SemanticTableClassificationAgentGenAiImpl) bind ISemanticTableClassificationAgent::class
    singleOf(::LinearizedContentConversionAgentGenAiImpl) bind ILinearizedContentConversionAgent::class
    singleOf(::FullPageNavigationAgentGenAiImpl) bind IFullPageNavigationAgent::class
    singleOf(::ContentRegionLocatorAgentGenAiImpl) bind IContentRegionLocatorAgent::class
    singleOf(::ContentExtractionAgentGenAiImpl) bind IContentExtractionAgent::class
    singleOf(::VisualContentExtractionAgentGenAiImpl) bind IVisualContentExtractionAgent::class
    singleOf(::WebpageReconnaissanceAgentGenAiImpl) bind IWebpageReconnaissanceAgent::class
    // Computer Use navigation agent (for CU benchmark comparison)
    singleOf(::ComputerUseNavigationAgentGenAiImpl) bind IComputerUseNavigationAgent::class

    // Markdown formatting agent (singleton for MarkdownFormattingService)
    singleOf(::MarkdownFormattingAgentGenAiImpl) bind IMarkdownFormattingAgent::class
    
    // Knowledge Graph agents
    singleOf(::EntityExtractionAgentGenAiImpl) bind IEntityExtractionAgent::class
    singleOf(::TextToCypherAgentGenAiImpl) bind ITextToCypherAgent::class

    // Request-scoped domain agents (used by request-scoped application services)
    requestScope {
        scopedOf(::AnswerReviewerAgentGenAiImpl) bind IAnswerReviewerAgent::class
        scopedOf(::StreamingAnswerSynthesisAgentGenAiImpl) bind IStreamingAnswerSynthesisAgent::class
        scopedOf(::IncrementalSynthesisAgentGenAiImpl) bind IIncrementalSynthesisAgent::class
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
        scopedOf(::StreamingAnswerAgentGenAiImpl) bind IStreamingAnswerAgent::class
        scopedOf(::PdfSourceEvalAgentGenAiImpl) bind IPdfSourceEvalAgent::class
        scopedOf(::MarkdownSourceEvalAgentGenAiImpl) bind IMarkdownSourceEvalAgent::class
        scopedOf(::FollowUpQueryDedupAgentGenAiImpl) bind IFollowUpQueryDedupAgent::class
        scopedOf(::UrlContextExtractionAgentGenAiImpl) bind IUrlContextExtractionAgent::class

        // Request-scoped domain services (user/auth related)
        scopedOf(::ApiKeyCryptoService) bind IApiKeyCryptoService::class
        scopedOf(::JwtService) bind IJwtService::class
    }
}