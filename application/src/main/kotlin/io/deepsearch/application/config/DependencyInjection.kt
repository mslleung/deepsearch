package io.deepsearch.application.config

import io.deepsearch.application.searchorchestrators.agenticbrowsersearch.AgenticBrowserSearchOrchestrator
import io.deepsearch.application.searchorchestrators.agenticbrowsersearch.IAgenticBrowserSearchOrchestrator
import io.deepsearch.application.searchorchestrators.cacheonlysearch.CacheOnlySearchOrchestrator
import io.deepsearch.application.searchorchestrators.cacheonlysearch.ICacheOnlySearchOrchestrator
import io.deepsearch.application.searchorchestrators.googlesearch.GoogleSearchOrchestrator
import io.deepsearch.application.searchorchestrators.googlesearch.IGoogleSearchOrchestrator
import io.deepsearch.application.services.*
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
    singleOf(::WebpageIconInterpretationService) bind IWebpageIconInterpretationService::class
    singleOf(::WebpageImageTextExtractionService) bind IWebpageImageTextExtractionService::class
    singleOf(::PopupContainerIdentificationService) bind IPopupContainerIdentificationService::class
    singleOf(::TableIdentificationService) bind ITableIdentificationService::class
    singleOf(::TableInterpretationService) bind ITableInterpretationService::class
    singleOf(::SemanticIdentificationService) bind ISemanticIdentificationService::class
    singleOf(::WebpageExtractionService) bind IWebpageExtractionService::class
    singleOf(::WebpageLinkDiscoveryService) bind IWebpageLinkDiscoveryService::class
    singleOf(::FileIngestionService) bind IFileIngestionService::class
    singleOf(::HttpContentTypeResolutionService) bind IHttpContentTypeResolutionService::class
    singleOf(::WebpageCacheService) bind IWebpageCacheService::class
    singleOf(::UrlContentProcessingService) bind IUrlContentProcessingService::class
    singleOf(::PeriodicIndexService) bind IPeriodicIndexService::class
    singleOf(::PeriodicIndexJobService) bind IPeriodicIndexJobService::class

    singleOf(::PeriodicIndexScheduler) { createdAtStart() }

    // Request-scoped services (user/auth related)
    requestScope {
        scopedOf(::ApiKeyService) bind IApiKeyService::class
        scopedOf(::AuthService) bind IAuthService::class
        scopedOf(::RateLimitService) bind IRateLimitService::class
        scopedOf(::UserSubscriptionService) bind IUserSubscriptionService::class
        scopedOf(::UsageService) bind IUsageService::class
        scopedOf(::AgenticBrowserSearchOrchestrator) bind IAgenticBrowserSearchOrchestrator::class
        scopedOf(::CacheOnlySearchOrchestrator) bind ICacheOnlySearchOrchestrator::class
        scopedOf(::GoogleSearchOrchestrator) bind IGoogleSearchOrchestrator::class
        scopedOf(::UserService) bind IUserService::class
        scopedOf(::SearchService) bind ISearchService::class
        scopedOf(::QuerySessionService) bind IQuerySessionService::class
        scopedOf(::PaymentService) bind IPaymentService::class
    }
}