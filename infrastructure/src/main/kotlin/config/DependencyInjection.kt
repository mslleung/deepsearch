package io.deepsearch.infrastructure.config

import io.deepsearch.domain.repositories.UserRepository
import io.deepsearch.infrastructure.database.DatabaseConfig
import io.deepsearch.infrastructure.repositories.ExposedUserRepository
import org.koin.dsl.module

val infrastructureModule = module {
    single(createdAtStart = true) { DatabaseConfig.configureDatabase() }

    single<UserRepository> { ExposedUserRepository() }
}