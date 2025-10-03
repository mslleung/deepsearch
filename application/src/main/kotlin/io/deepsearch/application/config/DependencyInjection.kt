package io.deepsearch.application.config

import io.deepsearch.application.searchstrategies.agenticbrowsersearch.AgenticBrowserSearchStrategy
import io.deepsearch.application.searchstrategies.agenticbrowsersearch.IAgenticBrowserSearchStrategy
import io.deepsearch.application.services.*
import io.deepsearch.domain.config.domainModule
import io.deepsearch.infrastructure.config.infrastructureModule
import org.koin.core.module.dsl.scopedOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.module.requestScope

val applicationModule = module {
    includes(domainModule)
    includes(infrastructureModule)

    requestScope {
        scopedOf(::AgenticBrowserSearchStrategy) bind IAgenticBrowserSearchStrategy::class
        scopedOf(::UserService) bind IUserService::class
        scopedOf(::SearchService) bind ISearchService::class
        scopedOf(::WebpageIconInterpretationService) bind IWebpageIconInterpretationService::class
        scopedOf(::PopupContainerIdentificationService) bind IPopupContainerIdentificationService::class
        scopedOf(::TableIdentificationService) bind ITableIdentificationService::class
        scopedOf(::TableInterpretationService) bind ITableInterpretationService::class
        scopedOf(::WebpageExtractionService) bind IWebpageExtractionService::class
        scopedOf(::PopupDismissService) bind IPopupDismissService::class
    }
}