package io.deepsearch.application.config

import io.deepsearch.application.searchorchestrators.agenticbrowsersearch.AgenticBrowserSearchOrchestrator
import io.deepsearch.application.searchorchestrators.agenticbrowsersearch.IAgenticBrowserSearchOrchestrator
import io.deepsearch.application.searchorchestrators.googlesearch.GoogleSearchOrchestrator
import io.deepsearch.application.searchorchestrators.googlesearch.IGoogleSearchOrchestrator
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
import io.deepsearch.application.services.IHttpContentTypeResolutionService
import io.deepsearch.application.services.IPdfConversionService
import io.deepsearch.application.services.IPrecacheJobRegistry
import io.deepsearch.application.services.IQuerySessionService
import io.deepsearch.application.services.IWebpageIconInterpretationService
import io.deepsearch.application.services.IWebpageImageTextExtractionService
import io.deepsearch.application.services.IWebpageLinkDiscoveryService
import io.deepsearch.application.services.PdfConversionService
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
import io.deepsearch.application.services.ISitemapLinkDiscoveryLockRegistry
import io.deepsearch.infrastructure.services.ITransactionService
import io.deepsearch.application.services.PrecacheJobRegistry
import io.deepsearch.application.services.QuerySessionService
import io.deepsearch.application.services.SitemapLinkDiscoveryLockRegistry
import io.deepsearch.infrastructure.services.TransactionService
import io.deepsearch.application.services.UrlProcessingLockRegistry
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
    singleOf(::PrecacheJobRegistry) bind IPrecacheJobRegistry::class

    singleOf(::UserService) bind IUserService::class
    singleOf(::SearchService) bind ISearchService::class
    singleOf(::WebpageIconInterpretationService) bind IWebpageIconInterpretationService::class
    singleOf(::PopupContainerIdentificationService) bind IPopupContainerIdentificationService::class
    singleOf(::WebpageExtractionService) bind IWebpageExtractionService::class
    singleOf(::TableIdentificationService) bind ITableIdentificationService::class
    singleOf(::TableInterpretationService) bind ITableInterpretationService::class
    singleOf(::SemanticIdentificationService) bind ISemanticIdentificationService::class
    singleOf(::WebpageCacheService) bind IWebpageCacheService::class
    singleOf(::UrlContentProcessingService) bind IUrlContentProcessingService::class
    singleOf(::AgenticBrowserSearchOrchestrator) bind IAgenticBrowserSearchOrchestrator::class
    singleOf(::GoogleSearchOrchestrator) bind IGoogleSearchOrchestrator::class
    singleOf(::WebpageImageTextExtractionService) bind IWebpageImageTextExtractionService::class
    singleOf(::WebpageLinkDiscoveryService) bind IWebpageLinkDiscoveryService::class
    singleOf(::PdfConversionService) bind IPdfConversionService::class
    singleOf(::HttpContentTypeResolutionService) bind IHttpContentTypeResolutionService::class
    singleOf(::QuerySessionService) bind IQuerySessionService::class
    singleOf(::TransactionService) bind ITransactionService::class
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