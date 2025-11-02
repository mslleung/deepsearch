package io.deepsearch.domain.config

import io.deepsearch.domain.agents.*
import io.deepsearch.domain.agents.googleadkimpl.*
import io.deepsearch.domain.agents.googleadkimpl.PdfToMarkdownAgentAdkImpl
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
import org.koin.core.module.dsl.createdAtStart
import org.koin.core.module.dsl.scopedOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.module.requestScope

val domainModule = module {
    singleOf(::ApplicationCoroutineScope) bind IApplicationCoroutineScope::class
    singleOf(::DefaultDispatcherProvider) bind IDispatcherProvider::class

    // OCR services
    singleOf(::BrowserRuntimePool) { createdAtStart() } bind IBrowserRuntimePool::class
    singleOf(::TesseractPoolImpl) { createdAtStart() } bind ITesseractPool::class

    requestScope {
        // domain agents (request scoped)
        scopedOf(::AggregateSearchResultsAgentAdkImpl) bind IAggregateSearchResultsAgent::class
        scopedOf(::BlinkTestAgentAdkImpl) bind IBlinkTestAgent::class
        scopedOf(::GoogleTextSearchAgentAdkImpl) bind IGoogleTextSearchAgent::class
        scopedOf(::GoogleSearchLinkDiscoveryAgentAdkImpl) bind IGoogleSearchLinkDiscoveryAgent::class
        scopedOf(::GoogleUrlContextSearchAgentImpl) bind IGoogleUrlContextSearchAgent::class
        scopedOf(::GoogleCombinedSearchAgentImpl) bind IGoogleCombinedSearchAgent::class
        scopedOf(::QueryExpansionAgentAdkImpl) bind IQueryExpansionAgent::class
        scopedOf(::TableIdentificationAgentAdkImpl) bind ITableIdentificationAgent::class
        scopedOf(::TableInterpretationAgentAdkImpl) bind ITableInterpretationAgent::class
        scopedOf(::PopupContainerIdentificationAgentAdkImpl) bind IPopupContainerIdentificationAgent::class
        scopedOf(::MultiIconInterpreterAgentAdkImpl) bind IMultiIconInterpreterAgent::class
        scopedOf(::MultiImageTextExtractionAgentAdkImpl) bind IMultiImageTextExtractionAgent::class
        scopedOf(::NavigationElementIdentificationAgentAdkImpl) bind INavigationElementIdentificationAgent::class
        scopedOf(::SemanticIdentificationAgentAdkImpl) bind ISemanticIdentificationAgent::class
        scopedOf(::MarkdownConversionAgentAdkImpl) bind IMarkdownConversionAgent::class
        scopedOf(::LinkRelevanceAnalysisAgentAdkImpl) bind ILinkRelevanceAnalysisAgent::class
        scopedOf(::GenerateAnswerAgentAdkImpl) bind IGenerateAnswerAgent::class
        scopedOf(::StreamingAnswerAgentAdkImpl) bind IStreamingAnswerAgent::class
        scopedOf(::PdfToMarkdownAgentAdkImpl) bind IPdfToMarkdownAgent::class

        // domain services
        scopedOf(::ApiKeyCryptoService) bind IApiKeyCryptoService::class
        scopedOf(::JwtService) bind IJwtService::class
        scopedOf(::OcrImageTextExtractionService) bind IOcrImageTextExtractionService::class
    }
}