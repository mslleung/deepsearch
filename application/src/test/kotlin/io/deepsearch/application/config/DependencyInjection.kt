package io.deepsearch.application.config

import io.deepsearch.application.searchstrategies.agenticbrowsersearch.AgenticBrowserSearchStrategy
import io.deepsearch.application.searchstrategies.agenticbrowsersearch.IAgenticBrowserSearchStrategy
import io.deepsearch.application.services.IPopupDismissService
import io.deepsearch.application.services.ISearchService
import io.deepsearch.application.services.IUserService
import io.deepsearch.application.services.IWebpageExtractionService
import io.deepsearch.application.services.PopupDismissService
import io.deepsearch.application.services.SearchService
import io.deepsearch.application.services.UserService
import io.deepsearch.application.services.WebpageExtractionService
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

    singleOf(::AgenticBrowserSearchStrategy) bind IAgenticBrowserSearchStrategy::class
    singleOf(::UserService) bind IUserService::class
    singleOf(::SearchService) bind ISearchService::class
    singleOf(::WebpageExtractionService) bind IWebpageExtractionService::class
    singleOf(::PopupDismissService) bind IPopupDismissService::class

    single<CoroutineDispatcher> { StandardTestDispatcher() }
}