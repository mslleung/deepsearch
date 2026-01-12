package io.deepsearch.application.config

import io.deepsearch.application.searchorchestrators.agenticbrowsersearch.AgenticBrowserSearchOrchestrator
import io.deepsearch.application.searchorchestrators.agenticbrowsersearch.AnswerSynthesisFacadeService
import io.deepsearch.application.searchorchestrators.agenticbrowsersearch.IAgenticBrowserSearchOrchestrator
import io.deepsearch.application.searchorchestrators.agenticbrowsersearch.IAnswerSynthesisFacadeService
import io.deepsearch.application.searchorchestrators.agenticbrowsersearch.ILinkDiscoveryFacadeService
import io.deepsearch.application.searchorchestrators.agenticbrowsersearch.ISourceEvaluationFacadeService
import io.deepsearch.application.searchorchestrators.agenticbrowsersearch.LinkDiscoveryFacadeService
import io.deepsearch.application.searchorchestrators.agenticbrowsersearch.SourceEvaluationFacadeService
import io.deepsearch.application.searchorchestrators.cacheonlysearch.CacheOnlySearchOrchestrator
import io.deepsearch.application.searchorchestrators.cacheonlysearch.ICacheOnlySearchOrchestrator
import io.deepsearch.application.searchorchestrators.googlesearch.GoogleSearchOrchestrator
import io.deepsearch.application.searchorchestrators.googlesearch.IGoogleSearchOrchestrator
import io.deepsearch.application.services.*
import io.deepsearch.application.services.batch.*
import io.deepsearch.domain.config.domainModule
import io.deepsearch.infrastructure.config.infrastructureModule
import org.koin.core.module.dsl.scopedOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.module.requestScope

import org.koin.core.module.dsl.createdAtStart

val applicationModule = module {
    includes(domainModule)
    includes(infrastructureModule)

    // Singleton services (shared across requests, stateless)
    singleOf(::UrlProcessingLockRegistry) bind IUrlProcessingLockRegistry::class
    singleOf(::SitemapLinkDiscoveryLockRegistry) bind ISitemapLinkDiscoveryLockRegistry::class
    singleOf(::PeriodicIndexJobRegistry) bind IPeriodicIndexJobRegistry::class

    // Stripe services
    singleOf(::StripePlanSyncService) { createdAtStart() }  bind IStripePlanSyncService::class

    // Singleton services needed by PeriodicIndexScheduler/PeriodicIndexJobRegistry
    singleOf(::UrlAccessService) bind IUrlAccessService::class
    singleOf(::LlmTokenUsageService) bind ILlmTokenUsageService::class
    
    // Search flow event tracking services (for timeline visualization)
    singleOf(::SearchFlowEventMapper)
    singleOf(::SearchFlowEventService) bind ISearchFlowEventService::class
    singleOf(::ExternalApiUsageService) bind IExternalApiUsageService::class
    singleOf(::CostCalculationService) bind ICostCalculationService::class
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
    singleOf(::LinkRelevanceHtmlService) bind ILinkRelevanceHtmlService::class
    singleOf(::ProxyResolutionService) bind IProxyResolutionService::class
    singleOf(::UrlContentProcessingService) bind IUrlContentProcessingService::class
    singleOf(::PeriodicIndexService) bind IPeriodicIndexService::class
    singleOf(::PeriodicIndexJobService) bind IPeriodicIndexJobService::class
    
    // Indexing services (handle both interactive fire-and-forget and batch modes)
    singleOf(::HybridSearchIndexingService) bind IHybridSearchIndexingService::class
    singleOf(::KnowledgeGraphIndexingService) bind IKnowledgeGraphIndexingService::class
    
    // Markdown indexing worker (background task processor, starts in init block)
    singleOf(::MarkdownIndexingWorker) bind IMarkdownIndexingWorker::class
    
    // WebpageCacheService depends on indexing services and worker
    singleOf(::WebpageCacheService) bind IWebpageCacheService::class
    
    // Knowledge Graph query services
    singleOf(::KgHybridRetrievalService) bind IKgHybridRetrievalService::class
    
    // Batch periodic index services (uses Gemini Batch API for cost-effective processing)
    singleOf(::BatchEventEmitter)
    singleOf(::CrawlAndExtractHandler)
    singleOf(::ContentLlmBatchHandler)
    singleOf(::TableInterpretationBatchHandler)
    singleOf(::ParallelEmbeddingAndKgHandler)
    singleOf(::KgEntityEmbeddingsHandler)
    singleOf(::FileUploadBackgroundWorker) // Background worker for file uploads to Gemini File Search
    singleOf(::BatchPeriodicIndexOrchestrator) bind IBatchPeriodicIndexOrchestrator::class
    singleOf(::BatchPeriodicIndexJobService) bind IBatchPeriodicIndexJobService::class

    singleOf(::PeriodicIndexScheduler) { createdAtStart() }

    // ProxySettingsService is stateless and needed by singletons (PeriodicIndexJobRegistry, CrawlAndExtractHandler)
    singleOf(::ProxySettingsService) bind IProxySettingsService::class

    // Request-scoped services (user/auth related)
    requestScope {
        scopedOf(::HtmlSourceEvalService) bind IHtmlSourceEvalService::class
        scopedOf(::PdfSourceEvalService) bind IPdfSourceEvalService::class
        scopedOf(::ApiKeyService) bind IApiKeyService::class
        scopedOf(::AuthService) bind IAuthService::class
        scopedOf(::UserSubscriptionService) bind IUserSubscriptionService::class
        scopedOf(::UsageService) bind IUsageService::class
        scopedOf(::QueryProcessingService) bind IQueryProcessingService::class
        
        // Agentic search facade services (group related services for cleaner orchestration)
        scopedOf(::LinkDiscoveryFacadeService) bind ILinkDiscoveryFacadeService::class
        scopedOf(::SourceEvaluationFacadeService) bind ISourceEvaluationFacadeService::class
        scopedOf(::AnswerSynthesisFacadeService) bind IAnswerSynthesisFacadeService::class
        
        // Agentic search orchestrator (now uses facade services, reduced to 11 dependencies)
        scopedOf(::AgenticBrowserSearchOrchestrator) bind IAgenticBrowserSearchOrchestrator::class
        
        scopedOf(::CacheOnlySearchOrchestrator) bind ICacheOnlySearchOrchestrator::class
        scopedOf(::GoogleSearchOrchestrator) bind IGoogleSearchOrchestrator::class
        scopedOf(::UserService) bind IUserService::class
        scopedOf(::SearchService) bind ISearchService::class
        scopedOf(::QuerySessionService) bind IQuerySessionService::class
        scopedOf(::PaymentService) bind IPaymentService::class
    }
}
