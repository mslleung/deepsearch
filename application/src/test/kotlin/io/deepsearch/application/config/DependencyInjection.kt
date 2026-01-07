package io.deepsearch.application.config

import io.deepsearch.application.searchorchestrators.agenticbrowsersearch.AgenticBrowserSearchOrchestrator
import io.deepsearch.application.searchorchestrators.agenticbrowsersearch.IAgenticBrowserSearchOrchestrator
import io.deepsearch.application.searchorchestrators.cacheonlysearch.CacheOnlySearchOrchestrator
import io.deepsearch.application.searchorchestrators.cacheonlysearch.ICacheOnlySearchOrchestrator
import io.deepsearch.application.searchorchestrators.googlesearch.GoogleSearchOrchestrator
import io.deepsearch.application.searchorchestrators.googlesearch.IGoogleSearchOrchestrator
import io.deepsearch.application.services.ApiKeyService
import io.deepsearch.application.services.AuthService
import io.deepsearch.application.services.BatchPeriodicIndexJobService
import io.deepsearch.application.services.batch.BatchEventEmitter
import io.deepsearch.application.services.batch.BatchPeriodicIndexOrchestrator
import io.deepsearch.application.services.batch.ContentLlmBatchHandler
import io.deepsearch.application.services.batch.CrawlAndExtractHandler
import io.deepsearch.application.services.batch.ParallelEmbeddingAndKgHandler
import io.deepsearch.application.services.batch.KgEntityEmbeddingsHandler
import io.deepsearch.application.services.batch.IBatchPeriodicIndexOrchestrator
import io.deepsearch.application.services.batch.TableInterpretationBatchHandler
import io.deepsearch.application.services.FileSearchService
import io.deepsearch.application.services.IBatchPeriodicIndexJobService
import io.deepsearch.domain.config.ApiKeyConfig
import io.deepsearch.domain.config.SerperConfig
import io.deepsearch.application.services.IPopupContainerIdentificationService
import io.deepsearch.application.services.ISearchService
import io.deepsearch.application.services.ISemanticIdentificationService
import io.deepsearch.application.services.ITableIdentificationService
import io.deepsearch.application.services.ITableInterpretationService
import io.deepsearch.application.services.IUserService
import io.deepsearch.application.services.IWebpageExtractionService
import io.deepsearch.application.services.HttpContentTypeResolutionService
import io.deepsearch.application.services.HtmlPreviewService
import io.deepsearch.application.services.HtmlSourceEvalService
import io.deepsearch.application.services.IHtmlPreviewService
import io.deepsearch.application.services.IHtmlSourceEvalService
import io.deepsearch.application.services.LinkRelevanceHtmlService
import io.deepsearch.application.services.ILinkRelevanceHtmlService
import io.deepsearch.application.services.IApiKeyService
import io.deepsearch.application.services.IAuthService
import io.deepsearch.application.services.IFileSearchService
import io.deepsearch.application.services.IHttpContentTypeResolutionService
import io.deepsearch.application.services.IPeriodicIndexJobRegistry
import io.deepsearch.application.services.IQuerySessionService
import io.deepsearch.application.services.IWebpageIconInterpretationService
import io.deepsearch.application.services.IWebpageImageTextExtractionService
import io.deepsearch.application.services.IWebpageLinkDiscoveryService
import io.deepsearch.application.services.PopupContainerIdentificationService
import io.deepsearch.application.services.SearchService
import io.deepsearch.application.services.SemanticIdentificationService
import io.deepsearch.application.services.TableIdentificationService
import io.deepsearch.application.services.TableInterpretationService
import io.deepsearch.application.services.UserService
import io.deepsearch.application.services.WebpageExtractionService
import io.deepsearch.application.services.WebpageIconInterpretationService
import io.deepsearch.application.services.WebpageImageTextExtractionService
import io.deepsearch.application.services.WebpageLinkDiscoveryService
import io.deepsearch.application.services.WebpageCacheService
import io.deepsearch.application.services.IWebpageCacheService
import io.deepsearch.application.services.UrlContentProcessingService
import io.deepsearch.application.services.IUrlContentProcessingService
import io.deepsearch.application.services.ILlmTokenUsageService
import io.deepsearch.application.services.IPeriodicIndexJobService
import io.deepsearch.application.services.IPeriodicIndexService
import io.deepsearch.application.services.IUrlProcessingLockRegistry
import io.deepsearch.application.services.ISitemapLinkDiscoveryLockRegistry
import io.deepsearch.application.services.IUrlAccessService
import io.deepsearch.application.services.IUsageService
import io.deepsearch.application.services.IUserSubscriptionService
import io.deepsearch.application.services.LlmTokenUsageService
import io.deepsearch.application.services.TestLlmTokenUsageService
import io.deepsearch.application.services.ProxySettingsService
import io.deepsearch.application.services.IProxySettingsService
import io.deepsearch.application.services.ProxyResolutionService
import io.deepsearch.application.services.IProxyResolutionService
import io.deepsearch.application.services.QueryProcessingService
import io.deepsearch.application.services.IQueryProcessingService
import io.deepsearch.application.services.HybridSearchIndexingService
import io.deepsearch.application.services.IHybridSearchIndexingService
import io.deepsearch.application.services.IMarkdownIndexingWorker
import io.deepsearch.application.services.KnowledgeGraphIndexingService
import io.deepsearch.application.services.IKnowledgeGraphIndexingService
import io.deepsearch.application.services.MarkdownIndexingWorker
import io.deepsearch.infrastructure.services.ITransactionService
import io.deepsearch.application.services.PeriodicIndexJobRegistry
import io.deepsearch.application.services.PeriodicIndexJobService
import io.deepsearch.application.services.PeriodicIndexScheduler
import io.deepsearch.application.services.PeriodicIndexService
import io.deepsearch.application.services.QuerySessionService
import io.deepsearch.application.services.SitemapLinkDiscoveryLockRegistry
import io.deepsearch.application.services.UrlAccessService
import io.deepsearch.infrastructure.services.TransactionService
import io.deepsearch.application.services.UrlProcessingLockRegistry
import io.deepsearch.application.services.UsageService
import io.deepsearch.application.services.UserSubscriptionService
import io.deepsearch.domain.config.domainBenchmarkTestModule
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.infrastructure.config.infrastructureBenchmarkTestModule
import io.deepsearch.infrastructure.config.infrastructureTestModule
import org.koin.core.module.dsl.createdAtStart
import org.koin.core.module.dsl.scopedOf
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
    singleOf(::WebpageExtractionService) bind IWebpageExtractionService::class
    singleOf(::WebpageLinkDiscoveryService) bind IWebpageLinkDiscoveryService::class
    singleOf(::FileSearchService) bind IFileSearchService::class
    singleOf(::HttpContentTypeResolutionService) bind IHttpContentTypeResolutionService::class
    singleOf(::HtmlPreviewService) bind IHtmlPreviewService::class
    singleOf(::HtmlSourceEvalService) bind IHtmlSourceEvalService::class
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