package io.deepsearch.domain.config

import com.microsoft.playwright.Playwright
import io.deepsearch.domain.services.AccessibilityService
import io.deepsearch.domain.services.AgenticSearchService
import io.deepsearch.domain.services.WebScrapingService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val domainTestModule = module {
    single { Playwright.create() }
    singleOf(::AccessibilityService)
    singleOf(::AgenticSearchService)
    singleOf(::WebScrapingService)
}