package io.deepsearch.infrastructure.config

import com.aallam.openai.client.OpenAI
import io.deepsearch.application.services.UserService
import io.deepsearch.application.services.WebScrapeService
import io.deepsearch.domain.repositories.UserRepository
import io.deepsearch.infrastructure.database.DatabaseConfig
import io.deepsearch.infrastructure.repositories.ExposedUserRepository
import org.koin.dsl.module

val infrastructureModule = module {
    single { DatabaseConfig.configureDatabase() }
    single<UserRepository> { ExposedUserRepository() }
    single {
        OpenAI(
            token = System.getenv("OPENAI_API_KEY") ?: "your-api-key-here"
        )
    }
}

val applicationModule = module {
    single { UserService(get()) }
    single { WebScrapeService(get()) }
} 