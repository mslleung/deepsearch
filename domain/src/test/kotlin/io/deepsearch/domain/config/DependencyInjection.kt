package io.deepsearch.domain.config

import com.microsoft.playwright.Playwright
import io.deepsearch.domain.agents.IGoogleTextSearchAgent
import io.deepsearch.domain.agents.IQueryExpansionAgent
import io.deepsearch.domain.agents.googleadkimpl.GoogleTextSearchAgentAdkImpl
import io.deepsearch.domain.agents.googleadkimpl.QueryExpansionAgentAdkImpl
import io.deepsearch.domain.services.AccessibilityService
import io.deepsearch.domain.services.AgenticSearchService
import io.deepsearch.domain.services.IQueryExpansionService
import io.deepsearch.domain.services.QueryExpansionService
import io.deepsearch.domain.services.WebScrapingService
import org.koin.core.module.dsl.scopedOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val domainTestModule = module {
    single { Playwright.create() }
    singleOf(::QueryExpansionService) bind IQueryExpansionService::class

    // Google ADK agent has its own lifecycle management, so we make it singleton
    singleOf(::QueryExpansionAgentAdkImpl) bind IQueryExpansionAgent::class
    singleOf(::GoogleTextSearchAgentAdkImpl) bind IGoogleTextSearchAgent::class
}