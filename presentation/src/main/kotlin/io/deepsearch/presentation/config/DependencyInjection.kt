package io.deepsearch.presentation.config

import io.deepsearch.application.config.applicationModule
import io.deepsearch.presentation.controllers.*
import org.koin.core.module.dsl.scopedOf
import org.koin.dsl.module
import org.koin.module.requestScope

val presentationModule = module {
    includes(applicationModule)

    requestScope {
        scopedOf(::AuthController)
        scopedOf(::ApiKeyController)
        scopedOf(::SearchController)
        scopedOf(::PeriodicIndexJobController)
        scopedOf(::PeriodicIndexController)
        scopedOf(::BatchPeriodicIndexController)
        scopedOf(::UsageController)
        scopedOf(::QuerySessionController)
        scopedOf(::PaymentController)
        scopedOf(::ProxySettingsController)
    }
} 