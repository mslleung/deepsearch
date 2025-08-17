package io.deepsearch.domain.config

import com.microsoft.playwright.Playwright
import io.deepsearch.domain.agents.IGoogleTextSearchAgent
import io.deepsearch.domain.agents.IBlinkTestAgent
import io.deepsearch.domain.agents.IVisualAnalysisAgent
import io.deepsearch.domain.agents.IAggregateSearchResultsAgent
import io.deepsearch.domain.agents.IQueryExpansionAgent
import io.deepsearch.domain.agents.googleadkimpl.GoogleTextSearchAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.BlinkTestAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.VisualAnalysisAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.QueryExpansionAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.AggregateSearchResultsAgentAdkImpl
import io.deepsearch.domain.services.AggregateSearchResultsService
import io.deepsearch.domain.services.BrowserService
import io.deepsearch.domain.services.IAggregateSearchResultsService
import io.deepsearch.domain.services.IBrowserService
import io.deepsearch.domain.services.IQueryExpansionService
import io.deepsearch.domain.services.IUnifiedSearchService
import io.deepsearch.domain.services.QueryExpansionService
import io.deepsearch.domain.services.UnifiedSearchService
import io.deepsearch.domain.searchstrategies.agenticbrowsersearch.AgenticBrowserSearchStrategy
import io.deepsearch.domain.searchstrategies.agenticbrowsersearch.IAgenticBrowserSearchStrategy
import io.deepsearch.domain.searchstrategies.googletextsearch.GoogleTextSearchStrategy
import io.deepsearch.domain.searchstrategies.googletextsearch.IGoogleTextSearchStrategy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val domainTestModule = module {
    singleOf(::AggregateSearchResultsService) bind IAggregateSearchResultsService::class
    singleOf(::BrowserService) bind IBrowserService::class
    singleOf(::QueryExpansionService) bind IQueryExpansionService::class
    singleOf(::UnifiedSearchService) bind IUnifiedSearchService::class
    singleOf(::AgenticBrowserSearchStrategy) bind IAgenticBrowserSearchStrategy::class
    singleOf(::GoogleTextSearchStrategy) bind IGoogleTextSearchStrategy::class

    // Google ADK agent has its own lifecycle management, so we make it singleton
    singleOf(::AggregateSearchResultsAgentAdkImpl) bind IAggregateSearchResultsAgent::class
    singleOf(::GoogleTextSearchAgentAdkImpl) bind IGoogleTextSearchAgent::class
    singleOf(::QueryExpansionAgentAdkImpl) bind IQueryExpansionAgent::class
    singleOf(::BlinkTestAgentAdkImpl) bind IBlinkTestAgent::class
    singleOf(::VisualAnalysisAgentAdkImpl) bind IVisualAnalysisAgent::class

    single<CoroutineDispatcher> { StandardTestDispatcher() }
}