package io.deepsearch.application.config

import io.deepsearch.application.services.UserService
import io.deepsearch.application.services.WebScrapeService
import io.deepsearch.domain.repositories.UserRepository
import io.deepsearch.infrastructure.database.DatabaseConfig
import io.deepsearch.infrastructure.repositories.ExposedUserRepository
import org.koin.core.module.dsl.singleOf
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import org.slf4j.Logger
import org.slf4j.LoggerFactory

val applicationModule = module {
    singleOf(::UserService)
    single { WebScrapeService(get { parametersOf(WebScrapeService::class.java) }) }

    // Logger factory - provides loggers for any class
    factory<Logger> { (forClass: Class<*>) ->
        LoggerFactory.getLogger(forClass)
    }
} 