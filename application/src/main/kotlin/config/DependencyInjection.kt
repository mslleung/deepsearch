package io.deepsearch.application.config

import io.deepsearch.application.services.UserService
import io.deepsearch.application.services.SearchService
import io.deepsearch.domain.config.domainModule
import io.deepsearch.infrastructure.config.infrastructureModule
import org.koin.core.module.dsl.scopedOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.koin.module.requestScope

val applicationModule = module {
    includes(domainModule)
    includes(infrastructureModule)

    requestScope {
        scopedOf(::UserService)
        scopedOf(::SearchService)
    }
} 