package io.deepsearch.application.config

import io.deepsearch.application.services.UserService
import io.deepsearch.application.services.SearchService
import io.deepsearch.domain.config.domainModule
import io.deepsearch.infrastructure.config.infrastructureModule
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val applicationModule = module {
    includes(domainModule)
    includes(infrastructureModule)

    singleOf(::UserService)
    singleOf(::SearchService)
} 