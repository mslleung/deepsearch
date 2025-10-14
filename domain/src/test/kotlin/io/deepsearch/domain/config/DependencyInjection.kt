package io.deepsearch.domain.config

import io.deepsearch.domain.agents.IAggregateSearchResultsAgent
import io.deepsearch.domain.agents.IBlinkTestAgent
import io.deepsearch.domain.agents.IGoogleCombinedSearchAgent
import io.deepsearch.domain.agents.IImageTextExtractionAgent
import io.deepsearch.domain.agents.IGoogleTextSearchAgent
import io.deepsearch.domain.agents.IGoogleSearchLinkDiscoveryAgent
import io.deepsearch.domain.agents.IGoogleUrlContextSearchAgent
import io.deepsearch.domain.agents.IIconInterpreterAgent
import io.deepsearch.domain.agents.IDirectAnswerAgent
import io.deepsearch.domain.agents.ILinkRelevanceAnalysisAgent
import io.deepsearch.domain.agents.IMarkdownConversionAgent
import io.deepsearch.domain.agents.INavigationElementIdentificationAgent
import io.deepsearch.domain.agents.IPopupContainerIdentificationAgent
import io.deepsearch.domain.agents.IQueryExpansionAgent
import io.deepsearch.domain.agents.ISemanticIdentificationAgent
import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.ITableInterpretationAgent
import io.deepsearch.domain.agents.googleadkimpl.AggregateSearchResultsAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.BlinkTestAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.GoogleCombinedSearchAgentImpl
import io.deepsearch.domain.agents.googleadkimpl.ImageTextExtractionAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.GoogleTextSearchAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.GoogleSearchLinkDiscoveryAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.GoogleUrlContextSearchAgentImpl
import io.deepsearch.domain.agents.googleadkimpl.IconInterpreterAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.DirectAnswerAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.LinkRelevanceAnalysisAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.MarkdownConversionAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.NavigationElementIdentificationAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.PopupContainerIdentificationAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.QueryExpansionAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.SemanticIdentificationAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.TableIdentificationAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.TableInterpretationAgentAdkImpl
import io.deepsearch.domain.browser.BrowserPool
import io.deepsearch.domain.browser.IBrowserPool
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import org.koin.core.module.dsl.scopedOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val domainTestModule = module {
    singleOf(::BrowserPool) bind IBrowserPool::class

    // Google ADK agent has its own lifecycle management, so we make it singleton
    singleOf(::AggregateSearchResultsAgentAdkImpl) bind IAggregateSearchResultsAgent::class
    singleOf(::BlinkTestAgentAdkImpl) bind IBlinkTestAgent::class
    singleOf(::GoogleTextSearchAgentAdkImpl) bind IGoogleTextSearchAgent::class
    singleOf(::GoogleSearchLinkDiscoveryAgentAdkImpl) bind IGoogleSearchLinkDiscoveryAgent::class
    singleOf(::GoogleUrlContextSearchAgentImpl) bind IGoogleUrlContextSearchAgent::class
    singleOf(::GoogleCombinedSearchAgentImpl) bind IGoogleCombinedSearchAgent::class
    singleOf(::QueryExpansionAgentAdkImpl) bind IQueryExpansionAgent::class
    singleOf(::TableIdentificationAgentAdkImpl) bind ITableIdentificationAgent::class
    singleOf(::IconInterpreterAgentAdkImpl) bind IIconInterpreterAgent::class
    singleOf(::TableInterpretationAgentAdkImpl) bind ITableInterpretationAgent::class
    singleOf(::PopupContainerIdentificationAgentAdkImpl) bind IPopupContainerIdentificationAgent::class
    singleOf(::NavigationElementIdentificationAgentAdkImpl) bind INavigationElementIdentificationAgent::class
    singleOf(::SemanticIdentificationAgentAdkImpl) bind ISemanticIdentificationAgent::class
    singleOf(::ImageTextExtractionAgentAdkImpl) bind IImageTextExtractionAgent::class
    singleOf(::DirectAnswerAgentAdkImpl) bind IDirectAnswerAgent::class
    singleOf(::MarkdownConversionAgentAdkImpl) bind IMarkdownConversionAgent::class
    singleOf(::LinkRelevanceAnalysisAgentAdkImpl) bind ILinkRelevanceAnalysisAgent::class

    single<CoroutineDispatcher> { StandardTestDispatcher() }
}


