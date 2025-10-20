package io.deepsearch.presentation.config

import io.deepsearch.application.config.applicationModule
import io.deepsearch.presentation.controllers.UserController
import io.deepsearch.presentation.controllers.SearchController
import io.deepsearch.presentation.controllers.CacheController
import io.deepsearch.presentation.controllers.PrecacheController
import org.koin.core.module.dsl.scopedOf
import org.koin.dsl.module
import org.koin.module.requestScope

val presentationModule = module {
    includes(applicationModule)

    requestScope {
        scopedOf(::UserController)
        scopedOf(::SearchController)
        scopedOf(::CacheController)
        scopedOf(::PrecacheController)
    }
} 