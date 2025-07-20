package io.deepsearch.presentation

import io.deepsearch.presentation.config.presentationModule
import io.deepsearch.presentation.routes.configureUserRoutes
import io.deepsearch.presentation.routes.configureWebScrapeRoutes
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.requestvalidation.*
import io.ktor.server.websocket.*
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    configureSerialization()
    configureDependencyInjection()
    configureWebSockets()
    configureRequestValidation()

    configureUserRoutes()
    configureWebScrapeRoutes()
}

private fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json()
    }
}

private fun Application.configureDependencyInjection() {
    install(Koin) {
        slf4jLogger()
        modules(presentationModule)
    }
}

private fun Application.configureWebSockets() {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
}

private fun Application.configureRequestValidation() {
    install(RequestValidation) {
        validate<String> { bodyText ->
            if (bodyText.isNotBlank()) {
                ValidationResult.Valid
            } else {
                ValidationResult.Invalid("Body text cannot be empty")
            }
        }
    }
}
