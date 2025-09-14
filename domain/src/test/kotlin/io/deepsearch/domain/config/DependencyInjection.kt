package io.deepsearch.domain.config

import io.deepsearch.domain.agents.IGoogleTextSearchAgent
import io.deepsearch.domain.agents.IBlinkTestAgent
import io.deepsearch.domain.agents.IAggregateSearchResultsAgent
import io.deepsearch.domain.agents.IGoogleUrlContextSearchAgent
import io.deepsearch.domain.agents.IQueryExpansionAgent
import io.deepsearch.domain.agents.googleadkimpl.GoogleTextSearchAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.BlinkTestAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.QueryExpansionAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.AggregateSearchResultsAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.GoogleUrlContextSearchAgentImpl
import io.deepsearch.domain.agents.googleadkimpl.GoogleCombinedSearchAgentImpl
import io.deepsearch.domain.agents.IGoogleCombinedSearchAgent
import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.googleadkimpl.TableIdentificationAgentAdkImpl
import io.deepsearch.domain.agents.IIconInterpreterAgent
import io.deepsearch.domain.agents.googleadkimpl.IconInterpreterAgentAdkImpl
import io.deepsearch.domain.browser.BrowserPool
import io.deepsearch.domain.browser.IBrowserPool
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val domainTestModule = module {
    singleOf(::BrowserPool) bind IBrowserPool::class

    // Google ADK agent has its own lifecycle management, so we make it singleton
    singleOf(::AggregateSearchResultsAgentAdkImpl) bind IAggregateSearchResultsAgent::class
    singleOf(::BlinkTestAgentAdkImpl) bind IBlinkTestAgent::class
    singleOf(::GoogleTextSearchAgentAdkImpl) bind IGoogleTextSearchAgent::class
    singleOf(::GoogleUrlContextSearchAgentImpl) bind IGoogleUrlContextSearchAgent::class
    singleOf(::GoogleCombinedSearchAgentImpl) bind IGoogleCombinedSearchAgent::class
    singleOf(::QueryExpansionAgentAdkImpl) bind IQueryExpansionAgent::class
    singleOf(::TableIdentificationAgentAdkImpl) bind ITableIdentificationAgent::class
    singleOf(::IconInterpreterAgentAdkImpl) bind IIconInterpreterAgent::class

    single<CoroutineDispatcher> { StandardTestDispatcher() }
}