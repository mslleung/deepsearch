package io.deepsearch.presentation.config

import io.deepsearch.application.config.applicationModule
import io.deepsearch.presentation.controllers.UserController
import io.deepsearch.presentation.controllers.SearchController
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

val presentationModule = module {
    includes(applicationModule)

    singleOf(::UserController)
    singleOf(::SearchController)

    // Logger factory - provides loggers for any class
    factory<Logger> { (forClass: KClass<*>) ->
        LoggerFactory.getLogger(forClass.java)
    }
} 