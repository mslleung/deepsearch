package io.deepsearch.application.config

import io.deepsearch.application.searchorchestrators.agenticbrowsersearch.AgenticBrowserSearchOrchestrator
import io.deepsearch.application.searchorchestrators.agenticbrowsersearch.IAgenticBrowserSearchOrchestrator
import io.deepsearch.application.searchorchestrators.googlesearch.GoogleSearchOrchestrator
import io.deepsearch.application.searchorchestrators.googlesearch.IGoogleSearchOrchestrator
import io.deepsearch.application.services.INormalizeUrlService
import io.deepsearch.application.services.INavigationElementRemovalService
import io.deepsearch.application.services.IPopupContainerIdentificationService
import io.deepsearch.application.services.ISearchService
import io.deepsearch.application.services.ISemanticIdentificationService
import io.deepsearch.application.services.ITableIdentificationService
import io.deepsearch.application.services.ITableInterpretationService
import io.deepsearch.application.services.IUserService
import io.deepsearch.application.services.IWebpageExtractionService
import io.deepsearch.application.services.HttpContentTypeResolutionService
import io.deepsearch.application.services.IHttpContentTypeResolutionService
import io.deepsearch.application.services.IPdfConversionService
import io.deepsearch.application.services.IPrecacheJobRegistry
import io.deepsearch.application.services.IQuerySessionService
import io.deepsearch.application.services.IWebpageIconInterpretationService
import io.deepsearch.application.services.IWebpageImageTextExtractionService
import io.deepsearch.application.services.IWebpageLinkDiscoveryService
import io.deepsearch.application.services.NavigationElementRemovalService
import io.deepsearch.application.services.PdfConversionService
import io.deepsearch.application.services.NormalizeUrlService
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
import io.deepsearch.application.services.IUrlProcessingLockRegistry
import io.deepsearch.application.services.PrecacheJobRegistry
import io.deepsearch.application.services.QuerySessionService
import io.deepsearch.application.services.UrlProcessingLockRegistry
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.infrastructure.config.infrastructureTestModule
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val applicationTestModule = module {
    includes(domainTestModule)
    includes(infrastructureTestModule)

    // Shared across test components
    singleOf(::UrlProcessingLockRegistry) bind IUrlProcessingLockRegistry::class
    singleOf(::PrecacheJobRegistry) bind IPrecacheJobRegistry::class

    singleOf(::UserService) bind IUserService::class
    singleOf(::SearchService) bind ISearchService::class
    singleOf(::WebpageIconInterpretationService) bind IWebpageIconInterpretationService::class
    singleOf(::PopupContainerIdentificationService) bind IPopupContainerIdentificationService::class
    singleOf(::WebpageExtractionService) bind IWebpageExtractionService::class
    singleOf(::TableIdentificationService) bind ITableIdentificationService::class
    singleOf(::TableInterpretationService) bind ITableInterpretationService::class
    singleOf(::NavigationElementRemovalService) bind INavigationElementRemovalService::class
    singleOf(::SemanticIdentificationService) bind ISemanticIdentificationService::class
    singleOf(::WebpageCacheService) bind IWebpageCacheService::class
    singleOf(::UrlContentProcessingService) bind IUrlContentProcessingService::class
    singleOf(::AgenticBrowserSearchOrchestrator) bind IAgenticBrowserSearchOrchestrator::class
    singleOf(::GoogleSearchOrchestrator) bind IGoogleSearchOrchestrator::class
    singleOf(::WebpageImageTextExtractionService) bind IWebpageImageTextExtractionService::class
    singleOf(::WebpageLinkDiscoveryService) bind IWebpageLinkDiscoveryService::class
    singleOf(::NormalizeUrlService) bind INormalizeUrlService::class
    singleOf(::PdfConversionService) bind IPdfConversionService::class
    singleOf(::HttpContentTypeResolutionService) bind IHttpContentTypeResolutionService::class
    singleOf(::QuerySessionService) bind IQuerySessionService::class
}