package io.deepsearch.domain.config

import io.deepsearch.domain.agents.IQueryExpansionAgent
import io.deepsearch.domain.agents.IBlinkTestAgent
import io.deepsearch.domain.agents.IAggregateSearchResultsAgent
import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.googleadkimpl.QueryExpansionAgentAdkImpl
import io.deepsearch.domain.agents.IGoogleTextSearchAgent
import io.deepsearch.domain.agents.IGoogleUrlContextSearchAgent
import io.deepsearch.domain.agents.googleadkimpl.GoogleTextSearchAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.BlinkTestAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.AggregateSearchResultsAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.GoogleUrlContextSearchAgentImpl
import io.deepsearch.domain.agents.googleadkimpl.GoogleCombinedSearchAgentImpl
import io.deepsearch.domain.agents.googleadkimpl.TableIdentificationAgentAdkImpl
import io.deepsearch.domain.agents.ITableInterpretationAgent
import io.deepsearch.domain.agents.googleadkimpl.TableInterpretationAgentAdkImpl
import io.deepsearch.domain.agents.IIconInterpreterAgent
import io.deepsearch.domain.agents.googleadkimpl.IconInterpreterAgentAdkImpl
import io.deepsearch.domain.agents.IGoogleCombinedSearchAgent
import io.deepsearch.domain.agents.IPopupIdentificationAgent
import io.deepsearch.domain.browser.BrowserPool
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.agents.googleadkimpl.PopupIdentificationAgentAdkImpl
import kotlinx.coroutines.CoroutineDispatcher
import org.koin.core.module.dsl.scopedOf
import org.koin.core.module.dsl.singleOf
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
        scopedOf(::PopupIdentificationAgentAdkImpl) bind IPopupIdentificationAgent::class
        scopedOf(::IconInterpreterAgentAdkImpl) bind IIconInterpreterAgent::class
    }
}