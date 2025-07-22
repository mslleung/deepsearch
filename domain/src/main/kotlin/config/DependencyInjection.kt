package io.deepsearch.domain.config

import io.deepsearch.domain.services.SearchService
import io.deepsearch.domain.services.WebScrapingService
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val domainModule = module {
    singleOf(::WebScrapingService)
    singleOf(::SearchService)
} 