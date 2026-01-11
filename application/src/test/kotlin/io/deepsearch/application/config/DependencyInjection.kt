package io.deepsearch.application.config

import io.deepsearch.application.searchorchestrators.agenticbrowsersearch.*
import io.deepsearch.application.searchorchestrators.cacheonlysearch.CacheOnlySearchOrchestrator
import io.deepsearch.application.searchorchestrators.cacheonlysearch.ICacheOnlySearchOrchestrator
import io.deepsearch.application.searchorchestrators.googlesearch.GoogleSearchOrchestrator
import io.deepsearch.application.searchorchestrators.googlesearch.IGoogleSearchOrchestrator
import io.deepsearch.application.services.*
import io.deepsearch.application.services.batch.*
import io.deepsearch.domain.config.ApiKeyConfig
import io.deepsearch.domain.config.SerperConfig
import io.deepsearch.domain.config.domainBenchmarkTestModule
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.infrastructure.config.infrastructureBenchmarkTestModule
import io.deepsearch.infrastructure.config.infrastructureTestModule
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

private val applicationCommonTestModule = module {
    // Test configuration
    single {
        ApiKeyConfig(
            hmacSecret = "test-hmac-secret-for-testing-purposes-only"
        )
    }
    single {
        SerperConfig(
            apiKey = System.getenv("SERPER_API_KEY") ?: "test-serper-api-key"
        )
    }

    // Shared across test components
    singleOf(::UrlProcessingLockRegistry) bind IUrlProcessingLockRegistry::class
    singleOf(::SitemapLinkDiscoveryLockRegistry) bind ISitemapLinkDiscoveryLockRegistry::class
    singleOf(::PeriodicIndexJobRegistry) bind IPeriodicIndexJobRegistry::class

    // no need for test
//    singleOf(::PeriodicIndexScheduler) { createdAtStart() }

    singleOf(::ApiKeyService) bind IApiKeyService::class
    singleOf(::AuthService) bind IAuthService::class
    singleOf(::UserSubscriptionService) bind IUserSubscriptionService::class
    singleOf(::UsageService) bind IUsageService::class
    
    // Agentic search facade services (group related services for cleaner orchestration)
    singleOf(::LinkDiscoveryFacadeService) bind ILinkDiscoveryFacadeService::class
    singleOf(::SourceEvaluationFacadeService) bind ISourceEvaluationFacadeService::class
    singleOf(::AnswerSynthesisFacadeService) bind IAnswerSynthesisFacadeService::class
    
    // Agentic search orchestrator (now uses facade services, reduced to 11 dependencies)
    singleOf(::AgenticBrowserSearchOrchestrator) bind IAgenticBrowserSearchOrchestrator::class
    
    singleOf(::CacheOnlySearchOrchestrator) bind ICacheOnlySearchOrchestrator::class
    singleOf(::GoogleSearchOrchestrator) bind IGoogleSearchOrchestrator::class
    singleOf(::UserService) bind IUserService::class
    singleOf(::SearchService) bind ISearchService::class
    singleOf(::WebpageIconInterpretationService) bind IWebpageIconInterpretationService::class
    singleOf(::WebpageImageTextExtractionService) bind IWebpageImageTextExtractionService::class
    singleOf(::PopupContainerIdentificationService) bind IPopupContainerIdentificationService::class
    singleOf(::TableIdentificationService) bind ITableIdentificationService::class
    singleOf(::TableInterpretationService) bind ITableInterpretationService::class
    singleOf(::SemanticIdentificationService) bind ISemanticIdentificationService::class
    singleOf(::MarkdownFormattingService) bind IMarkdownFormattingService::class
    singleOf(::WebpageExtractionService) bind IWebpageExtractionService::class
    singleOf(::WebpageLinkDiscoveryService) bind IWebpageLinkDiscoveryService::class
    singleOf(::FileSearchService) bind IFileSearchService::class
    singleOf(::HttpContentTypeResolutionService) bind IHttpContentTypeResolutionService::class
    singleOf(::HtmlPreviewService) bind IHtmlPreviewService::class
    singleOf(::PdfPreviewService) bind IPdfPreviewService::class
    singleOf(::HtmlSourceEvalService) bind IHtmlSourceEvalService::class
    singleOf(::PdfSourceEvalService) bind IPdfSourceEvalService::class
    singleOf(::LinkRelevanceHtmlService) bind ILinkRelevanceHtmlService::class
    singleOf(::WebpageCacheService) bind IWebpageCacheService::class
    singleOf(::UrlContentProcessingService) bind IUrlContentProcessingService::class
    singleOf(::UrlAccessService) bind IUrlAccessService::class
    singleOf(::QuerySessionService) bind IQuerySessionService::class
    singleOf(::PeriodicIndexJobService) bind IPeriodicIndexJobService::class
    singleOf(::PeriodicIndexService) bind IPeriodicIndexService::class
    singleOf(::ProxySettingsService) bind IProxySettingsService::class
    singleOf(::ProxyResolutionService) bind IProxyResolutionService::class
    singleOf(::QueryProcessingService) bind IQueryProcessingService::class
    
    // Indexing services (handle both interactive fire-and-forget and batch modes)
    singleOf(::HybridSearchIndexingService) bind IHybridSearchIndexingService::class
    singleOf(::KnowledgeGraphIndexingService) bind IKnowledgeGraphIndexingService::class
    
    // Markdown indexing worker (starts automatically via init block)
    singleOf(::MarkdownIndexingWorker) bind IMarkdownIndexingWorker::class
    
    // Batch periodic index services
    singleOf(::BatchEventEmitter)
    singleOf(::CrawlAndExtractHandler)
    singleOf(::ContentLlmBatchHandler)
    singleOf(::TableInterpretationBatchHandler)
    singleOf(::ParallelEmbeddingAndKgHandler)
    singleOf(::KgEntityEmbeddingsHandler)
    singleOf(::FileUploadBackgroundWorker) // Background worker for file uploads
    singleOf(::BatchPeriodicIndexOrchestrator) bind IBatchPeriodicIndexOrchestrator::class
    singleOf(::BatchPeriodicIndexJobService) bind IBatchPeriodicIndexJobService::class

    // test stubs
    singleOf(::TestLlmTokenUsageService) bind ILlmTokenUsageService::class
}

val applicationTestModule = module {
    includes(domainTestModule)
    includes(infrastructureTestModule)
    includes(applicationCommonTestModule)
}

val applicationBenchmarkTestModule = module {
    includes(domainBenchmarkTestModule)
    includes(infrastructureBenchmarkTestModule)
    includes(applicationCommonTestModule)
}
