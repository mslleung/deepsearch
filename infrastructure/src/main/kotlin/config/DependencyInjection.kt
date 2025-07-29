package io.deepsearch.infrastructure.config

import io.deepsearch.domain.repositories.UserRepository
import io.deepsearch.infrastructure.database.DatabaseConfig
import io.deepsearch.infrastructure.repositories.ExposedUserRepository
import org.koin.dsl.module
import org.koin.module.requestScope

val infrastructureModule = module {
    single(createdAtStart = true) { DatabaseConfig.configureDatabase() }

    requestScope {
        scoped<UserRepository> { ExposedUserRepository() }
    }
}