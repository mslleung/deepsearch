package io.deepsearch.application.config

import io.deepsearch.application.searchstrategies.agenticbrowsersearch.AgenticBrowserSearchStrategy
import io.deepsearch.application.searchstrategies.agenticbrowsersearch.IAgenticBrowserSearchStrategy
import io.deepsearch.application.searchstrategies.googlesearch.GoogleSearchStrategy
import io.deepsearch.application.searchstrategies.googlesearch.IGoogleSearchStrategy
import io.deepsearch.domain.config.domainTestModule
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val applicationTestModule = module {
    includes(domainTestModule)

    singleOf(::AgenticBrowserSearchStrategy) bind IAgenticBrowserSearchStrategy::class
    singleOf(::GoogleSearchStrategy) bind IGoogleSearchStrategy::class
}