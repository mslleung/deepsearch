package io.deepsearch.application.config

import io.deepsearch.application.searchorchestrators.agenticbrowsersearch.AgenticBrowserSearchOrchestrator
import io.deepsearch.application.searchorchestrators.agenticbrowsersearch.IAgenticBrowserSearchOrchestrator
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

    // Shared across requests
    singleOf(::UrlProcessingLockRegistry) bind IUrlProcessingLockRegistry::class
    singleOf(::SitemapLinkDiscoveryLockRegistry) bind ISitemapLinkDiscoveryLockRegistry::class
    singleOf(::PeriodicIndexJobRegistry) bind IPeriodicIndexJobRegistry::class
    singleOf(::PeriodicIndexScheduler) { createdAtStart() }

    requestScope {
        scopedOf(::ApiKeyService) bind IApiKeyService::class
        scopedOf(::AuthService) bind IAuthService::class
        scopedOf(::RateLimitService) bind IRateLimitService::class
        scopedOf(::UserSubscriptionService) bind IUserSubscriptionService::class
        scopedOf(::UsageService) bind IUsageService::class
        scopedOf(::PeriodicIndexJobService) bind IPeriodicIndexJobService::class
        scopedOf(::PeriodicIndexService) bind IPeriodicIndexService::class
        scopedOf(::LlmTokenUsageService) bind ILlmTokenUsageService::class
        scopedOf(::AgenticBrowserSearchOrchestrator) bind IAgenticBrowserSearchOrchestrator::class
        scopedOf(::GoogleSearchOrchestrator) bind IGoogleSearchOrchestrator::class
        scopedOf(::UserService) bind IUserService::class
        scopedOf(::SearchService) bind ISearchService::class
        scopedOf(::WebpageIconInterpretationService) bind IWebpageIconInterpretationService::class
        scopedOf(::WebpageImageTextExtractionService) bind IWebpageImageTextExtractionService::class
        scopedOf(::PopupContainerIdentificationService) bind IPopupContainerIdentificationService::class
        scopedOf(::TableIdentificationService) bind ITableIdentificationService::class
        scopedOf(::TableInterpretationService) bind ITableInterpretationService::class
        scopedOf(::SemanticIdentificationService) bind ISemanticIdentificationService::class
        scopedOf(::WebpageExtractionService) bind IWebpageExtractionService::class
        scopedOf(::WebpageLinkDiscoveryService) bind IWebpageLinkDiscoveryService::class
        scopedOf(::PdfConversionService) bind IPdfConversionService::class
        scopedOf(::HttpContentTypeResolutionService) bind IHttpContentTypeResolutionService::class
        scopedOf(::WebpageCacheService) bind IWebpageCacheService::class
        scopedOf(::UrlContentProcessingService) bind IUrlContentProcessingService::class
        scopedOf(::UrlAccessService) bind IUrlAccessService::class
        scopedOf(::QuerySessionService) bind IQuerySessionService::class
    }
}