package io.deepsearch.infrastructure.config

import io.deepsearch.application.services.UserService
import io.deepsearch.application.services.WebScrapeService
import io.deepsearch.domain.repositories.UserRepository
import io.deepsearch.infrastructure.database.DatabaseConfig
import io.deepsearch.infrastructure.repositories.ExposedUserRepository
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import org.slf4j.Logger
import org.slf4j.LoggerFactory

val infrastructureModule = module {
    single { DatabaseConfig.configureDatabase() }
    single<UserRepository> { ExposedUserRepository() }
}

val applicationModule = module {
    single { UserService(get()) }
    single { WebScrapeService(get(parameters = { parametersOf(WebScrapeService::class.java) })) }
    
    // Logger factory - provides loggers for any class
    factory<Logger> { (forClass: Class<*>) -> 
        LoggerFactory.getLogger(forClass) 
    }
} 