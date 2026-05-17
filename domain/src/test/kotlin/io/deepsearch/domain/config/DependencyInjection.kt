package io.deepsearch.domain.config

import com.google.genai.Client
import io.deepsearch.domain.agents.*
import io.deepsearch.domain.agents.googlegenaiimpl.*
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
import io.deepsearch.domain.services.GeminiTextEmbeddingServiceImpl
import io.deepsearch.domain.services.IApiKeyCryptoService
import io.deepsearch.domain.services.IBoundingBoxDerivationService
import io.deepsearch.domain.services.ICssSelectorConstructionService
import io.deepsearch.domain.services.IHtmlToMarkdownService
import io.deepsearch.domain.services.IJsoupDomService
import io.deepsearch.domain.services.IJwtService
import io.deepsearch.domain.services.HtmlToMarkdownService
import io.deepsearch.domain.services.INormalizeUrlService
import io.deepsearch.domain.services.ISerperService
import io.deepsearch.domain.services.ITextEmbeddingService
import io.deepsearch.domain.services.JsoupDomService
import io.deepsearch.domain.services.JwtService
import io.deepsearch.domain.services.NormalizeUrlService
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
import io.deepsearch.domain.services.GeminiFileSearchService
import io.deepsearch.domain.services.IGeminiFileSearchService
import io.deepsearch.domain.services.IImageDimensionService
import io.deepsearch.domain.services.ImageDimensionService
import io.deepsearch.domain.services.ITableGridDetectorService
import io.deepsearch.domain.services.IRecursiveTableDiscoveryService
import io.deepsearch.domain.services.ISemanticListConverter
import io.deepsearch.domain.services.ISemanticTableConverter
import io.deepsearch.domain.services.DomDiffService
import io.deepsearch.domain.services.IDomDiffService
import io.deepsearch.domain.services.IImageProcessingService
import io.deepsearch.domain.services.ImageProcessingService
import io.deepsearch.domain.services.SemanticListConverter
import io.deepsearch.domain.services.SemanticTableConverter
import io.deepsearch.domain.detection.TableGridDetectorService
import io.deepsearch.domain.detection.RecursiveTableDiscoveryService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

private val domainCommonTestModule = module {
    // Test configuration
    single {
        SerperConfig(
            apiKey = System.getenv("SERPER_API_KEY") ?: "test-serper-api-key"
        )
    }
    single {
        val apiKey = System.getenv("GOOGLE_API_KEY")?.ifBlank { "test-gemini-api-key" } ?: "test-gemini-api-key"
        Client.builder()
            .apiKey(apiKey)
            .build()
    }
    single {
        EnvironmentConfig(
            isDevelopmentMode = true
        )
    }
    single {
        DeepSearchBrowserConfig(
            url = "http://localhost:8090"
        )
    }
    single {
        ProxyrackHttpConfig(
            endpoint = System.getenv("PROXYRACK_ENDPOINT") ?: "test-endpoint:10000",
            username = System.getenv("PROXYRACK_USERNAME") ?: "test-user",
            apiKey = System.getenv("PROXYRACK_API_KEY") ?: "test-api-key"
        )
    }

    singleOf(::ApplicationCoroutineScope) bind IApplicationCoroutineScope::class

    singleOf(::RemoteBrowserPool) bind IBrowserPool::class
    singleOf(::TesseractPoolImpl) bind ITesseractPool::class

    // GenAI agents as singletons for tests
    singleOf(::FileSearchQueryAgentGenAiImpl) bind IFileSearchQueryAgent::class
    singleOf(::AnswerReviewerAgentGenAiImpl) bind IAnswerReviewerAgent::class
    singleOf(::StreamingAnswerSynthesisAgentGenAiImpl) bind IStreamingAnswerSynthesisAgent::class
    singleOf(::IncrementalSynthesisAgentGenAiImpl) bind IIncrementalSynthesisAgent::class
    singleOf(::BlinkTestAgentGenAiImpl) bind IBlinkTestAgent::class
    singleOf(::DirectAnswerAgentGenAiImpl) bind IDirectAnswerAgent::class
    singleOf(::GenerateAnswerAgentGenAiImpl) bind IGenerateAnswerAgent::class
    singleOf(::GoogleCombinedSearchAgentGenAiImpl) bind IGoogleCombinedSearchAgent::class
    singleOf(::GoogleSearchLinkDiscoveryAgentGenAiImpl) bind IGoogleSearchLinkDiscoveryAgent::class
    singleOf(::GoogleTextSearchAgentGenAiImpl) bind IGoogleTextSearchAgent::class
    singleOf(::GoogleUrlContextSearchAgentGenAiImpl) bind IGoogleUrlContextSearchAgent::class
    singleOf(::IconInterpreterAgentGenAiImpl) bind IIconInterpreterAgent::class
    singleOf(::LinkRelevanceAnalysisAgentGenAiImpl) bind ILinkRelevanceAnalysisAgent::class
    singleOf(::MarkdownConversionAgentGenAiImpl) bind IMarkdownConversionAgent::class
    singleOf(::MultiIconInterpreterAgentGenAiImpl) bind IMultiIconInterpreterAgent::class
    singleOf(::ImageClassificationAgentGenAiImpl) bind IImageClassificationAgent::class
    singleOf(::ImageDescriptionAgentGenAiImpl) bind IImageDescriptionAgent::class
    singleOf(::TableExtractionAgentGenAiImpl) bind ITableExtractionAgent::class
    singleOf(::AgenticTableConversionAgentGenAiImpl) bind IAgenticTableConversionAgent::class
    singleOf(::PopupContainerIdentificationAgentGenAiImpl) bind IPopupContainerIdentificationAgent::class
    singleOf(::QueryBreakdownAgentGenAiImpl) bind IQueryBreakdownAgent::class
    singleOf(::QueryExpansionAgentGenAiImpl) bind IQueryExpansionAgent::class
    singleOf(::SemanticIdentificationAgentGenAiImpl) bind ISemanticIdentificationAgent::class
    singleOf(::StreamingAnswerAgentGenAiImpl) bind IStreamingAnswerAgent::class
    singleOf(::PdfSourceEvalAgentGenAiImpl) bind IPdfSourceEvalAgent::class
    singleOf(::MarkdownSourceEvalAgentGenAiImpl) bind IMarkdownSourceEvalAgent::class
    singleOf(::TableInterpretationAgentGenAiImpl) bind ITableInterpretationAgent::class
    singleOf(::VisualIdentificationAgentGenAiImpl) bind IVisualIdentificationAgent::class
    singleOf(::SemanticTableClassificationAgentGenAiImpl) bind ISemanticTableClassificationAgent::class
    singleOf(::LinearizedContentConversionAgentGenAiImpl) bind ILinearizedContentConversionAgent::class
    singleOf(::FullPageNavigationAgentGenAiImpl) bind IFullPageNavigationAgent::class
    singleOf(::ContentExtractionAgentGenAiImpl) bind IContentExtractionAgent::class
    singleOf(::WebpageReconnaissanceAgentGenAiImpl) bind IWebpageReconnaissanceAgent::class
    singleOf(::TextLinkDiscoveryAgentGenAiImpl) bind ITextLinkDiscoveryAgent::class
    singleOf(::FollowUpQueryDedupAgentGenAiImpl) bind IFollowUpQueryDedupAgent::class
    singleOf(::UrlContextExtractionAgentGenAiImpl) bind IUrlContextExtractionAgent::class
    
    // Markdown formatting
    singleOf(::MarkdownFormattingAgentGenAiImpl) bind IMarkdownFormattingAgent::class

    // Knowledge Graph agents
    singleOf(::EntityExtractionAgentGenAiImpl) bind IEntityExtractionAgent::class
    singleOf(::TextToCypherAgentGenAiImpl) bind ITextToCypherAgent::class

    // domain services
    singleOf(::AdaptiveRateLimiter) bind IAdaptiveRateLimiter::class
    singleOf(::ApiKeyCryptoService) bind IApiKeyCryptoService::class
    singleOf(::BoundingBoxDerivationService) bind IBoundingBoxDerivationService::class
    singleOf(::CssSelectorConstructionService) bind ICssSelectorConstructionService::class
    singleOf(::JsoupDomService) bind IJsoupDomService::class
    singleOf(::HtmlToMarkdownService) bind IHtmlToMarkdownService::class
    singleOf(::JwtService) bind IJwtService::class
    singleOf(::OcrImageTextExtractionService) bind IOcrImageTextExtractionService::class
    singleOf(::SerperService) bind ISerperService::class
    singleOf(::GeminiTextEmbeddingServiceImpl) bind ITextEmbeddingService::class
    singleOf(::NormalizeUrlService) bind INormalizeUrlService::class
    singleOf(::GeminiBatchServiceImpl) bind IGeminiBatchService::class
    singleOf(::GeminiFileSearchService) bind IGeminiFileSearchService::class
    singleOf(::ImageDimensionService) bind IImageDimensionService::class
    singleOf(::TableGridDetectorService) bind ITableGridDetectorService::class
    singleOf(::RecursiveTableDiscoveryService) bind IRecursiveTableDiscoveryService::class
    singleOf(::SemanticTableConverter) bind ISemanticTableConverter::class
    singleOf(::SemanticListConverter) bind ISemanticListConverter::class
    singleOf(::ImageProcessingService) bind IImageProcessingService::class
    singleOf(::DomDiffService) bind IDomDiffService::class
    singleOf(::ProxyAwareHttpClientFactory) bind IProxyAwareHttpClientFactory::class
    singleOf(::ProxyTestService) bind IProxyTestService::class
    singleOf(::FreeProxySyncService)
    singleOf(::FreeProxyPool)
    singleOf(::FreeProxyProvider) bind IFreeProxyProvider::class
}

val domainTestModule = module {
    includes(domainCommonTestModule)

    single<CoroutineDispatcher> { StandardTestDispatcher() }

    single<IDispatcherProvider> {
        val testDispatcher = get<CoroutineDispatcher>()
        object : IDispatcherProvider {
            override val io = testDispatcher
            override val default = testDispatcher
            override val main = testDispatcher
            override val unconfined = testDispatcher
        }
    }
}

val domainBenchmarkTestModule = module {
    includes(domainCommonTestModule)

    single<CoroutineDispatcher> { StandardTestDispatcher() }

    singleOf(::DefaultDispatcherProvider) bind IDispatcherProvider::class
}


