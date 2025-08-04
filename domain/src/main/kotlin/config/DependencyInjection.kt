package io.deepsearch.domain.config

import io.deepsearch.domain.services.*
import org.koin.core.module.dsl.scopedOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.module.requestScope
import services.BrowserService
import services.IBrowserService

val domainModule = module {
    requestScope {
        scopedOf(::AgenticSearchService) bind IAgenticSearchService::class
        scopedOf(::BrowserService) bind IBrowserService::class
    }
} 