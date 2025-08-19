package io.deepsearch.domain.config

import io.deepsearch.domain.agents.IQueryExpansionAgent
import io.deepsearch.domain.agents.IBlinkTestAgent
import io.deepsearch.domain.agents.IVisualAnalysisAgent
import io.deepsearch.domain.agents.IAggregateSearchResultsAgent
import io.deepsearch.domain.agents.googleadkimpl.QueryExpansionAgentAdkImpl
import io.deepsearch.domain.agents.IGoogleTextSearchAgent
import io.deepsearch.domain.agents.IGoogleUrlContextSearchAgent
import io.deepsearch.domain.agents.googleadkimpl.GoogleTextSearchAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.BlinkTestAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.VisualAnalysisAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.AggregateSearchResultsAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.GoogleUrlContextSearchAgentImpl
import io.deepsearch.domain.agents.googleadkimpl.GoogleCombinedSearchAgentImpl
import io.deepsearch.domain.agents.IGoogleCombinedSearchAgent
import io.deepsearch.domain.searchstrategies.agenticbrowsersearch.AgenticBrowserSearchStrategy
import io.deepsearch.domain.searchstrategies.googlesearch.GoogleSearchStrategy
import io.deepsearch.domain.searchstrategies.agenticbrowsersearch.IAgenticBrowserSearchStrategy
import io.deepsearch.domain.searchstrategies.googlesearch.IGoogleSearchStrategy
import io.deepsearch.domain.services.*
import org.koin.core.module.dsl.scopedOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.module.requestScope

val domainModule = module {
    requestScope {
        // domain services
        scopedOf(::UnifiedSearchService) bind IUnifiedSearchService::class
        scopedOf(::BrowserService) bind IBrowserService::class
        scopedOf(::QueryExpansionService) bind IQueryExpansionService::class
        scopedOf(::AggregateSearchResultsService) bind IAggregateSearchResultsService::class

        scopedOf(::AgenticBrowserSearchStrategy) bind IAgenticBrowserSearchStrategy::class
        scopedOf(::GoogleSearchStrategy) bind IGoogleSearchStrategy::class
    }

    // Google ADK agent has its own lifecycle management, so we make it singleton
    singleOf(::AggregateSearchResultsAgentAdkImpl) bind IAggregateSearchResultsAgent::class
    singleOf(::BlinkTestAgentAdkImpl) bind IBlinkTestAgent::class
    singleOf(::GoogleTextSearchAgentAdkImpl) bind IGoogleTextSearchAgent::class
    singleOf(::GoogleUrlContextSearchAgentImpl) bind IGoogleUrlContextSearchAgent::class
    singleOf(::GoogleCombinedSearchAgentImpl) bind IGoogleCombinedSearchAgent::class
    singleOf(::QueryExpansionAgentAdkImpl) bind IQueryExpansionAgent::class
    singleOf(::VisualAnalysisAgentAdkImpl) bind IVisualAnalysisAgent::class
}
