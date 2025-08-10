package io.deepsearch.domain.config

import io.deepsearch.domain.agents.IQueryExpansionAgent
import io.deepsearch.domain.agents.googleadkimpl.QueryExpansionAgentAdkImpl
import io.deepsearch.domain.services.*
import org.koin.core.module.dsl.scopedOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.module.requestScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

val domainModule = module {
    requestScope {
        scopedOf(::AgenticSearchService) bind IAgenticSearchService::class
        scopedOf(::BrowserService) bind IBrowserService::class
        scopedOf(::QueryExpansionService) bind IQueryExpansionService::class
    }

    // Google ADK agent has its own lifecycle management, so we make it singleton
    singleOf(::QueryExpansionAgentAdkImpl) bind IQueryExpansionAgent::class
}
