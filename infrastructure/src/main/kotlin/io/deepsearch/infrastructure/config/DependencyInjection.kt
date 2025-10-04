package io.deepsearch.infrastructure.config

import io.deepsearch.domain.repositories.*
import io.deepsearch.infrastructure.database.DatabaseConfig
import io.deepsearch.infrastructure.repositories.*
import org.koin.core.module.dsl.scopedOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.module.requestScope

val infrastructureModule = module {
    single(createdAtStart = true) { DatabaseConfig.configureDatabase() }

    requestScope {
        scopedOf(::ExposedUserRepository) bind IUserRepository::class
        scopedOf(::ExposedWebpageIconRepository) bind IWebpageIconRepository::class
        scopedOf(::ExposedWebpagePopupRepository) bind IWebpagePopupRepository::class
        scopedOf(::ExposedWebpageTableRepository) bind IWebpageTableRepository::class
        scopedOf(::ExposedWebpageTableInterpretationRepository) bind IWebpageTableInterpretationRepository::class
        scopedOf(::ExposedWebpageNavigationElementRepository) bind IWebpageNavigationElementRepository::class
    }
}