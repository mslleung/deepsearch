package io.deepsearch.domain.config

import com.microsoft.playwright.Playwright
import io.deepsearch.domain.agents.IGoogleTextSearchAgent
import io.deepsearch.domain.agents.IAggregateSearchResultsAgent
import io.deepsearch.domain.agents.IQueryExpansionAgent
import io.deepsearch.domain.agents.googleadkimpl.GoogleTextSearchAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.QueryExpansionAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.AggregateSearchResultsAgentAdkImpl
import io.deepsearch.domain.services.AggregateSearchResultsService
import io.deepsearch.domain.services.IAggregateSearchResultsService
import io.deepsearch.domain.services.IQueryExpansionService
import io.deepsearch.domain.services.IUnifiedSearchService
import io.deepsearch.domain.services.QueryExpansionService
import io.deepsearch.domain.services.UnifiedSearchService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val domainTestModule = module {
    single { Playwright.create() }
    singleOf(::AggregateSearchResultsService) bind IAggregateSearchResultsService::class
    singleOf(::QueryExpansionService) bind IQueryExpansionService::class
    singleOf(::UnifiedSearchService) bind IUnifiedSearchService::class

    // Google ADK agent has its own lifecycle management, so we make it singleton
    singleOf(::AggregateSearchResultsAgentAdkImpl) bind IAggregateSearchResultsAgent::class
    singleOf(::GoogleTextSearchAgentAdkImpl) bind IGoogleTextSearchAgent::class
    singleOf(::QueryExpansionAgentAdkImpl) bind IQueryExpansionAgent::class

    single<CoroutineDispatcher> { StandardTestDispatcher() }
}