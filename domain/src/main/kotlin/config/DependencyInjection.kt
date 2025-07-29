package io.deepsearch.domain.config

import com.microsoft.playwright.Playwright
import io.deepsearch.domain.services.AccessibilityService
import io.deepsearch.domain.services.SearchService
import io.deepsearch.domain.services.WebScrapingService
import org.koin.core.module.dsl.scopedOf
import org.koin.dsl.module
import org.koin.module.requestScope

val domainModule = module {
    requestScope {
        scoped { Playwright.create() }
        scopedOf(::AccessibilityService)
        scopedOf(::SearchService)
        scopedOf(::WebScrapingService)
    }
} 