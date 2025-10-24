package io.deepsearch.application.config

import io.deepsearch.application.searchorchestrators.agenticbrowsersearch.AgenticBrowserSearchOrchestrator
import io.deepsearch.application.searchorchestrators.agenticbrowsersearch.IAgenticBrowserSearchOrchestrator
import io.deepsearch.application.services.*
import io.deepsearch.domain.config.domainModule
import io.deepsearch.infrastructure.config.infrastructureModule
import org.koin.core.module.dsl.scopedOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.module.requestScope

val applicationModule = module {
    includes(domainModule)
    includes(infrastructureModule)

    // Shared across requests
    singleOf(::UrlProcessingLockRegistry) bind IUrlProcessingLockRegistry::class
    singleOf(::PrecacheJobRegistry) bind IPrecacheJobRegistry::class

    requestScope {
        scopedOf(::PrecacheService) bind IPrecacheService::class
        scopedOf(::CacheQueryService) bind ICacheQueryService::class
        scopedOf(::AgenticBrowserSearchOrchestrator) bind IAgenticBrowserSearchOrchestrator::class
        scopedOf(::UserService) bind IUserService::class
        scopedOf(::SearchService) bind ISearchService::class
        scopedOf(::WebpageIconInterpretationService) bind IWebpageIconInterpretationService::class
        scopedOf(::WebpageImageTextExtractionService) bind IWebpageImageTextExtractionService::class
        scopedOf(::PopupContainerIdentificationService) bind IPopupContainerIdentificationService::class
        scopedOf(::TableIdentificationService) bind ITableIdentificationService::class
        scopedOf(::TableInterpretationService) bind ITableInterpretationService::class
        scopedOf(::NavigationElementRemovalService) bind INavigationElementRemovalService::class
        scopedOf(::SemanticIdentificationService) bind ISemanticIdentificationService::class
        scopedOf(::WebpageExtractionService) bind IWebpageExtractionService::class
        scopedOf(::WebpageLinkDiscoveryService) bind IWebpageLinkDiscoveryService::class
        scopedOf(::NormalizeUrlService) bind INormalizeUrlService::class
        scopedOf(::PdfConversionService) bind IPdfConversionService::class
        scopedOf(::HttpContentTypeResolutionService) bind IHttpContentTypeResolutionService::class
        scopedOf(::WebpageCacheService) bind IWebpageCacheService::class
        scopedOf(::UrlContentProcessingService) bind IUrlContentProcessingService::class
        scopedOf(::QuerySessionService) bind IQuerySessionService::class
    }
}