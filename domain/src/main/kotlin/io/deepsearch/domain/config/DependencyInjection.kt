package io.deepsearch.domain.config

import io.deepsearch.domain.agents.*
import io.deepsearch.domain.agents.googleadkimpl.*
import io.deepsearch.domain.browser.BrowserPool
import io.deepsearch.domain.browser.IBrowserPool
import org.koin.core.module.dsl.scopedOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.module.requestScope

val domainModule = module {
    requestScope {
        // domain services

        scopedOf(::BrowserPool) bind IBrowserPool::class

        // domain agents (request scoped)
        scopedOf(::AggregateSearchResultsAgentAdkImpl) bind IAggregateSearchResultsAgent::class
        scopedOf(::BlinkTestAgentAdkImpl) bind IBlinkTestAgent::class
        scopedOf(::GoogleTextSearchAgentAdkImpl) bind IGoogleTextSearchAgent::class
        scopedOf(::GoogleUrlContextSearchAgentImpl) bind IGoogleUrlContextSearchAgent::class
        scopedOf(::GoogleCombinedSearchAgentImpl) bind IGoogleCombinedSearchAgent::class
        scopedOf(::QueryExpansionAgentAdkImpl) bind IQueryExpansionAgent::class
        scopedOf(::TableIdentificationAgentAdkImpl) bind ITableIdentificationAgent::class
        scopedOf(::TableInterpretationAgentAdkImpl) bind ITableInterpretationAgent::class
        scopedOf(::PopupContainerIdentificationAgentAdkImpl) bind IPopupContainerIdentificationAgent::class
        scopedOf(::IconInterpreterAgentAdkImpl) bind IIconInterpreterAgent::class
    }
}