package io.deepsearch.presentation.admin.config

import io.deepsearch.application.config.sharedApplicationModule
import io.deepsearch.presentation.admin.controllers.*
import org.koin.core.module.dsl.scopedOf
import org.koin.dsl.module
import org.koin.module.requestScope

val adminPresentationModule = module {
    includes(sharedApplicationModule)

    requestScope {
        scopedOf(::AdminUserController)
        scopedOf(::AdminSubscriptionPlanController)
        scopedOf(::AdminUserSubscriptionController)
        scopedOf(::AdminApiKeyController)
        scopedOf(::AdminUsageController)
        scopedOf(::AdminQuerySessionController)
        scopedOf(::AdminPeriodicIndexJobController)
        scopedOf(::AdminBatchPeriodicIndexJobController)
    }
}

