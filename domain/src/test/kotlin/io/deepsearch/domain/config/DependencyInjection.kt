package io.deepsearch.domain.config

import com.google.genai.Client
import io.deepsearch.domain.agents.*
import io.deepsearch.domain.agents.googlegenaiimpl.*
import io.deepsearch.domain.browser.BrowserPool
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.services.IOcrImageTextExtractionService
import io.deepsearch.domain.ocr.ITesseractPool
import io.deepsearch.domain.services.OcrImageTextExtractionService
import io.deepsearch.domain.ocr.TesseractPoolImpl
import io.deepsearch.domain.ratelimit.AdaptiveRateLimiter
import io.deepsearch.domain.ratelimit.IAdaptiveRateLimiter
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import org.koin.core.module.dsl.scopedOf
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

    singleOf(::ApplicationCoroutineScope) bind IApplicationCoroutineScope::class

    singleOf(::BrowserPool) bind IBrowserPool::class
    singleOf(::TesseractPoolImpl) bind ITesseractPool::class

    // GenAI agents as singletons for tests
    singleOf(::FileSearchQueryAgentGenAiImpl) bind IFileSearchQueryAgent::class
    singleOf(::AnswerReviewerAgentGenAiImpl) bind IAnswerReviewerAgent::class
    singleOf(::AnswerSynthesisAgentGenAiImpl) bind IAnswerSynthesisAgent::class
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
    singleOf(::TableExtractionAgentGenAiImpl) bind ITableExtractionAgent::class
    singleOf(::PopupContainerIdentificationAgentGenAiImpl) bind IPopupContainerIdentificationAgent::class
    singleOf(::QueryBreakdownAgentGenAiImpl) bind IQueryBreakdownAgent::class
    singleOf(::QueryExpansionAgentGenAiImpl) bind IQueryExpansionAgent::class
    singleOf(::SerpQueryOptimizationAgentGenAiImpl) bind ISerpQueryOptimizationAgent::class
    singleOf(::SemanticIdentificationAgentGenAiImpl) bind ISemanticIdentificationAgent::class
    singleOf(::StreamingAnswerAgentGenAiImpl) bind IStreamingAnswerAgent::class
    singleOf(::StreamingSourceShortlistAgentGenAiImpl) bind IStreamingSourceShortlistAgent::class
    singleOf(::TableIdentificationAgentGenAiImpl) bind ITableIdentificationAgent::class
    singleOf(::TableInterpretationAgentGenAiImpl) bind ITableInterpretationAgent::class
    singleOf(::TextLinkDiscoveryAgentGenAiImpl) bind ITextLinkDiscoveryAgent::class

    // domain services
    singleOf(::AdaptiveRateLimiter) bind IAdaptiveRateLimiter::class
    singleOf(::ApiKeyCryptoService) bind IApiKeyCryptoService::class
    singleOf(::CssSelectorConstructionService) bind ICssSelectorConstructionService::class
    singleOf(::JwtService) bind IJwtService::class
    singleOf(::OcrImageTextExtractionService) bind IOcrImageTextExtractionService::class
    singleOf(::SerperService) bind ISerperService::class
    singleOf(::GeminiTextEmbeddingServiceImpl) bind ITextEmbeddingService::class
    singleOf(::NormalizeUrlService) bind INormalizeUrlService::class
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


