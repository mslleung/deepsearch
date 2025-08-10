package io.deepsearch.infrastructure.config

import io.deepsearch.domain.repositories.IUserRepository
import io.deepsearch.infrastructure.database.DatabaseConfig
import io.deepsearch.infrastructure.repositories.ExposedUserRepository
import org.koin.core.module.dsl.scopedOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.module.requestScope

val infrastructureModule = module {
    single(createdAtStart = true) { DatabaseConfig.configureDatabase() }

    requestScope {
        scopedOf(::ExposedUserRepository) bind IUserRepository::class
    }
}