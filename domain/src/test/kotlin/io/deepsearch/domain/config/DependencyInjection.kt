package io.deepsearch.domain.config

import io.deepsearch.domain.agents.IAggregateSearchResultsAgent
import io.deepsearch.domain.agents.IBlinkTestAgent
import io.deepsearch.domain.agents.IGoogleCombinedSearchAgent
import io.deepsearch.domain.agents.IMultiImageTextExtractionAgent
import io.deepsearch.domain.agents.IGoogleTextSearchAgent
import io.deepsearch.domain.agents.IGoogleSearchLinkDiscoveryAgent
import io.deepsearch.domain.agents.IGoogleUrlContextSearchAgent
import io.deepsearch.domain.agents.IDirectAnswerAgent
import io.deepsearch.domain.agents.IGenerateAnswerAgent
import io.deepsearch.domain.agents.IStreamingAnswerAgent
import io.deepsearch.domain.agents.ILinkRelevanceAnalysisAgent
import io.deepsearch.domain.agents.IMarkdownConversionAgent
import io.deepsearch.domain.agents.IMultiIconInterpreterAgent
import io.deepsearch.domain.agents.INavigationElementIdentificationAgent
import io.deepsearch.domain.agents.IPdfToMarkdownAgent
import io.deepsearch.domain.agents.IPopupContainerIdentificationAgent
import io.deepsearch.domain.agents.IQueryExpansionAgent
import io.deepsearch.domain.agents.ISemanticIdentificationAgent
import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.ITableInterpretationAgent
import io.deepsearch.domain.agents.googleadkimpl.AggregateSearchResultsAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.BlinkTestAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.GoogleCombinedSearchAgentImpl
import io.deepsearch.domain.agents.googleadkimpl.MultiImageTextExtractionAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.GoogleTextSearchAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.GoogleSearchLinkDiscoveryAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.GoogleUrlContextSearchAgentImpl
import io.deepsearch.domain.agents.googleadkimpl.DirectAnswerAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.GenerateAnswerAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.StreamingAnswerAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.LinkRelevanceAnalysisAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.MarkdownConversionAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.MultiIconInterpreterAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.NavigationElementIdentificationAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.PdfToMarkdownAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.PopupContainerIdentificationAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.QueryExpansionAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.SemanticIdentificationAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.TableIdentificationAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.TableInterpretationAgentAdkImpl
import io.deepsearch.domain.browser.BrowserRuntimePool
import io.deepsearch.domain.browser.IBrowserRuntimePool
import io.deepsearch.domain.services.IOcrImageTextExtractionService
import io.deepsearch.domain.ocr.ITesseractPool
import io.deepsearch.domain.services.OcrImageTextExtractionService
import io.deepsearch.domain.ocr.TesseractPoolImpl
import io.deepsearch.domain.services.ApiKeyCryptoService
import io.deepsearch.domain.services.IApiKeyCryptoService
import io.deepsearch.domain.services.IJwtService
import io.deepsearch.domain.services.JwtService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import org.koin.core.module.dsl.scopedOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

private val domainCommonTestModule = module {
    singleOf(::ApplicationCoroutineScope) bind IApplicationCoroutineScope::class
    singleOf(::BrowserRuntimePool) bind IBrowserRuntimePool::class
    
    // OCR services
    singleOf(::TesseractPoolImpl) bind ITesseractPool::class

    // Google ADK agent has its own lifecycle management, so we make it singleton
    singleOf(::AggregateSearchResultsAgentAdkImpl) bind IAggregateSearchResultsAgent::class
    singleOf(::BlinkTestAgentAdkImpl) bind IBlinkTestAgent::class
    singleOf(::GoogleTextSearchAgentAdkImpl) bind IGoogleTextSearchAgent::class
    singleOf(::GoogleSearchLinkDiscoveryAgentAdkImpl) bind IGoogleSearchLinkDiscoveryAgent::class
    singleOf(::GoogleUrlContextSearchAgentImpl) bind IGoogleUrlContextSearchAgent::class
    singleOf(::GoogleCombinedSearchAgentImpl) bind IGoogleCombinedSearchAgent::class
    singleOf(::QueryExpansionAgentAdkImpl) bind IQueryExpansionAgent::class
    singleOf(::TableIdentificationAgentAdkImpl) bind ITableIdentificationAgent::class
    singleOf(::TableInterpretationAgentAdkImpl) bind ITableInterpretationAgent::class
    singleOf(::PopupContainerIdentificationAgentAdkImpl) bind IPopupContainerIdentificationAgent::class
    singleOf(::NavigationElementIdentificationAgentAdkImpl) bind INavigationElementIdentificationAgent::class
    singleOf(::SemanticIdentificationAgentAdkImpl) bind ISemanticIdentificationAgent::class
    singleOf(::MultiImageTextExtractionAgentAdkImpl) bind IMultiImageTextExtractionAgent::class
    singleOf(::DirectAnswerAgentAdkImpl) bind IDirectAnswerAgent::class
    singleOf(::GenerateAnswerAgentAdkImpl) bind IGenerateAnswerAgent::class
    singleOf(::StreamingAnswerAgentAdkImpl) bind IStreamingAnswerAgent::class
    singleOf(::MarkdownConversionAgentAdkImpl) bind IMarkdownConversionAgent::class
    singleOf(::LinkRelevanceAnalysisAgentAdkImpl) bind ILinkRelevanceAnalysisAgent::class
    singleOf(::PdfToMarkdownAgentAdkImpl) bind IPdfToMarkdownAgent::class
    singleOf(::MultiIconInterpreterAgentAdkImpl) bind IMultiIconInterpreterAgent::class

    // domain services
    singleOf(::ApiKeyCryptoService) bind IApiKeyCryptoService::class
    singleOf(::JwtService) bind IJwtService::class
    singleOf(::OcrImageTextExtractionService) bind IOcrImageTextExtractionService::class
}

val domainTestModule = module {
    includes(domainCommonTestModule)

    single<CoroutineDispatcher> { StandardTestDispatcher() }

    single<IDispatcherProvider> {
        val testDispatcher = get<CoroutineDispatcher>()
        object : IDispatcherProvider {
            override val io = testDispatcher
            override val default = testDispatcher
            override val main = testDispatcher
            override val unconfined = testDispatcher
        }
    }
}

val domainBenchmarkTestModule = module {
    includes(domainCommonTestModule)

    single<CoroutineDispatcher> { StandardTestDispatcher() }

    singleOf(::DefaultDispatcherProvider) bind IDispatcherProvider::class
}


