package io.deepsearch.application.config

import io.deepsearch.application.searchstrategies.agenticbrowsersearch.AgenticBrowserSearchStrategy
import io.deepsearch.application.searchstrategies.agenticbrowsersearch.IAgenticBrowserSearchStrategy
import io.deepsearch.application.searchstrategies.googlesearch.GoogleSearchStrategy
import io.deepsearch.application.searchstrategies.googlesearch.IGoogleSearchStrategy
import io.deepsearch.application.services.INavigationElementRemovalService
import io.deepsearch.application.services.IPopupContainerIdentificationService
import io.deepsearch.application.services.IPopupDismissService
import io.deepsearch.application.services.ISearchService
import io.deepsearch.application.services.ITableIdentificationService
import io.deepsearch.application.services.ITableInterpretationService
import io.deepsearch.application.services.IUserService
import io.deepsearch.application.services.IWebpageExtractionService
import io.deepsearch.application.services.IWebpageIconInterpretationService
import io.deepsearch.application.services.NavigationElementRemovalService
import io.deepsearch.application.services.PopupContainerIdentificationService
import io.deepsearch.application.services.PopupDismissService
import io.deepsearch.application.services.SearchService
import io.deepsearch.application.services.TableIdentificationService
import io.deepsearch.application.services.TableInterpretationService
import io.deepsearch.application.services.UserService
import io.deepsearch.application.services.WebpageExtractionService
import io.deepsearch.application.services.WebpageIconInterpretationService
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.infrastructure.config.infrastructureTestModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val applicationTestModule = module {
    includes(domainTestModule)
    includes(infrastructureTestModule)

    singleOf(::UserService) bind IUserService::class
    singleOf(::SearchService) bind ISearchService::class
    singleOf(::WebpageIconInterpretationService) bind IWebpageIconInterpretationService::class
    singleOf(::PopupContainerIdentificationService) bind IPopupContainerIdentificationService::class
    singleOf(::WebpageExtractionService) bind IWebpageExtractionService::class
    singleOf(::PopupDismissService) bind IPopupDismissService::class
    singleOf(::TableIdentificationService) bind ITableIdentificationService::class
    singleOf(::TableInterpretationService) bind ITableInterpretationService::class
    singleOf(::NavigationElementRemovalService) bind INavigationElementRemovalService::class
    singleOf(::AgenticBrowserSearchStrategy) bind IAgenticBrowserSearchStrategy::class
    singleOf(::GoogleSearchStrategy) bind IGoogleSearchStrategy::class

    single<CoroutineDispatcher> { StandardTestDispatcher() }
}