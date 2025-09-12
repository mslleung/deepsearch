package io.deepsearch.application.config

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import org.koin.dsl.module

val applicationTestModule = module {
    includes(domainTestModule)
//    singleOf(::AgenticBrowserSearchStrategy) bind IAgenticBrowserSearchStrategy::class
//    singleOf(::GoogleSearchStrategy) bind IGoogleSearchStrategy::class
//
//    singleOf(::BrowserPool) bind IBrowserPool::class
//
//    // Google ADK agent has its own lifecycle management, so we make it singleton
//    singleOf(::AggregateSearchResultsAgentAdkImpl) bind IAggregateSearchResultsAgent::class
//    singleOf(::BlinkTestAgentAdkImpl) bind IBlinkTestAgent::class
//    singleOf(::GoogleTextSearchAgentAdkImpl) bind IGoogleTextSearchAgent::class
//    singleOf(::GoogleUrlContextSearchAgentImpl) bind IGoogleUrlContextSearchAgent::class
//    singleOf(::GoogleCombinedSearchAgentImpl) bind IGoogleCombinedSearchAgent::class
//    singleOf(::QueryExpansionAgentAdkImpl) bind IQueryExpansionAgent::class
//    singleOf(::TableIdentificationAgentAdkImpl) bind ITableIdentificationAgent::class

    single<CoroutineDispatcher> { StandardTestDispatcher() }
}