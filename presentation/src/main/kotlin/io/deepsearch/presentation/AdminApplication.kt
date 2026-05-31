package io.deepsearch.presentation

import io.deepsearch.domain.config.ApiKeyConfig
import io.deepsearch.domain.config.DeepSearchBrowserConfig
import io.deepsearch.domain.config.DatabaseEncryptionConfig
import io.deepsearch.domain.config.EnvironmentConfig
import io.deepsearch.domain.config.PostgresConfig
import io.deepsearch.presentation.admin.config.adminPresentationModule
import io.deepsearch.presentation.admin.routes.*
import io.deepsearch.presentation.config.configureStatusPages
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.cors.routing.*
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.adminModule() {
    configureSerialization()
    configureCORS()
    configureDependencyInjection()
    configureStatusPages()

    // Configure admin routes (no authentication required)
    configureAdminUserRoutes()
    configureAdminSubscriptionRoutes()
    configureAdminApiKeyRoutes()
    configureAdminUsageRoutes()
    configureAdminQuerySessionRoutes()
    configureAdminPeriodicIndexJobRoutes()
    configureAdminBatchPeriodicIndexJobRoutes()
}

private fun Application.configureSerialization() {
    install(ServerContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }
}

private fun Application.configureCORS() {
    install(CORS) {
        // Allow admin frontend
        allowHost("localhost:3001")
        allowHost("127.0.0.1:3001")
        
        allowHeader(HttpHeaders.ContentType)
        
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Patch)
        
        allowCredentials = true
        
        allowHeader("X-Requested-With")
    }
}

private fun Application.configureDependencyInjection() {
    install(Koin) {
        slf4jLogger()
        modules(
            adminPresentationModule,
            module {
                single {
                    ApiKeyConfig(
                        hmacSecret = environment.config.property("apiKey.hmacSecret").getString()
                    )
                }
                single {
                    DatabaseEncryptionConfig(
                        encryptionSecret = environment.config.property("database.encryptionSecret").getString()
                    )
                }
                single {
                    PostgresConfig(
                        host = environment.config.property("database.postgres.host").getString(),
                        port = environment.config.property("database.postgres.port").getString().toInt(),
                        database = environment.config.property("database.postgres.database").getString(),
                        username = environment.config.property("database.postgres.username").getString(),
                        password = environment.config.property("database.postgres.password").getString()
                    )
                }
                single {
                    EnvironmentConfig(
                        isDevelopmentMode = environment.config.property("ktor.development").getString().toBoolean()
                    )
                }
                single {
                    DeepSearchBrowserConfig(
                        url = environment.config.property("deepsearchBrowser.url").getString()
                    )
                }
                single {
                    HttpClient(OkHttp) {
                        install(ClientContentNegotiation) {
                            json(Json {
                                ignoreUnknownKeys = true
                            })
                        }
                    }
                }
            })
    }
}

