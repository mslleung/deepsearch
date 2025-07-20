package io.deepsearch.presentation.config

import io.deepsearch.presentation.controllers.UserController
import io.deepsearch.presentation.controllers.WebScrapeController
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.slf4j.Logger
import org.slf4j.LoggerFactory

val presentationModule = module {
    singleOf(::UserController)
    singleOf(::WebScrapeController)

    // Logger factory - provides loggers for any class
    factory<Logger> { (forClass: Class<*>) ->
        LoggerFactory.getLogger(forClass)
    }
} 