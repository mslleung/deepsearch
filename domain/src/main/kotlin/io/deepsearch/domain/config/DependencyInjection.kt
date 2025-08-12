package io.deepsearch.domain.config

import io.deepsearch.domain.agents.IQueryExpansionAgent
import io.deepsearch.domain.agents.IAggregateSearchResultsAgent
import io.deepsearch.domain.agents.googleadkimpl.QueryExpansionAgentAdkImpl
import io.deepsearch.domain.agents.IGoogleTextSearchAgent
import io.deepsearch.domain.agents.googleadkimpl.GoogleTextSearchAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.AggregateSearchResultsAgentAdkImpl
import io.deepsearch.domain.searchstrategies.AgenticBrowserSearchStrategy
import io.deepsearch.domain.searchstrategies.GoogleTextSearchStrategy
import io.deepsearch.domain.searchstrategies.IAgenticBrowserSearchStrategy
import io.deepsearch.domain.searchstrategies.IGoogleTextSearchStrategy
import io.deepsearch.domain.services.*
import org.koin.core.module.dsl.scopedOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.module.requestScope

val domainModule = module {
    requestScope {
        // domain services
        scopedOf(::AgenticSearchService) bind IAgenticSearchService::class
        scopedOf(::BrowserService) bind IBrowserService::class
        scopedOf(::QueryExpansionService) bind IQueryExpansionService::class
        scopedOf(::AggregateSearchResultsService) bind IAggregateSearchResultsService::class

        scopedOf(::AgenticBrowserSearchStrategy) bind IAgenticBrowserSearchStrategy::class
        scopedOf(::GoogleTextSearchStrategy) bind IGoogleTextSearchStrategy::class
    }

    // Google ADK agent has its own lifecycle management, so we make it singleton
    singleOf(::QueryExpansionAgentAdkImpl) bind IQueryExpansionAgent::class
    singleOf(::GoogleTextSearchAgentAdkImpl) bind IGoogleTextSearchAgent::class
    singleOf(::AggregateSearchResultsAgentAdkImpl) bind IAggregateSearchResultsAgent::class
}
