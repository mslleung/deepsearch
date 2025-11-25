package io.deepsearch.infrastructure.config

import io.deepsearch.domain.config.DatabaseEncryptionConfig
import io.deepsearch.domain.config.PostgresConfig
import io.deepsearch.domain.repositories.*
import io.deepsearch.infrastructure.database.*
import io.deepsearch.infrastructure.repositories.*
import io.deepsearch.infrastructure.services.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import org.koin.core.module.dsl.createdAtStart
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

private val infrastructureCommonTestModule = module {
    // Test encryption config
    single {
        DatabaseEncryptionConfig(
            encryptionSecret = System.getenv("DATABASE_ENCRYPTION_SECRET")
        )
    }
    single {
        PostgresConfig(
            host = System.getenv("DB_HOST"),
            port = System.getenv("DB_PORT").toInt(),
            database = System.getenv("DB_NAME"),
            username = System.getenv("DB_USERNAME"),
            password = System.getenv("DB_PASSWORD")
        )
    }
    
    // Database encryption service
    singleOf(::DatabaseConfigurationService) { createdAtStart() } bind IDatabaseConfigurationService::class
    singleOf(::DatabaseCryptoService) bind IDatabaseCryptoService::class
    singleOf(::TransactionService) bind ITransactionService::class

    // All table instances (singletons, depend on DatabaseCryptoService)
    singleOf(::UserTable)
    singleOf(::ApiKeyTable)
    singleOf(::UserSubscriptionTable)
    singleOf(::WebpageIconCacheTable)
    singleOf(::WebpageImageCacheTable)
    singleOf(::WebpagePopupCacheTable)
    singleOf(::WebpageTableCacheTable)
    singleOf(::WebpageTableInterpretationCacheTable)
    singleOf(::WebpageSemanticElementCacheTable)
    singleOf(::WebpageMarkdownCacheTable)
    singleOf(::PdfMarkdownCacheTable)
    singleOf(::QuerySessionTable)
    singleOf(::UrlAccessTable)
    singleOf(::PeriodicIndexJobTable)
    singleOf(::PeriodicIndexConfigTable)
    singleOf(::SitemapCacheTable)
    singleOf(::LlmTokenUsageTable)
    
    // Repositories as singletons in tests (no request scope needed for testing)
    singleOf(::ExposedUserRepository) bind IUserRepository::class
    singleOf(::ExposedApiKeyRepository) bind IApiKeyRepository::class
    singleOf(::ExposedWebpageIconRepository) bind IWebpageIconRepository::class
    singleOf(::ExposedWebpageImageRepository) bind IWebpageImageRepository::class
    singleOf(::ExposedWebpagePopupRepository) bind IWebpagePopupRepository::class
    singleOf(::ExposedWebpageTableRepository) bind IWebpageTableRepository::class
    singleOf(::ExposedWebpageTableInterpretationRepository) bind IWebpageTableInterpretationRepository::class
    singleOf(::ExposedWebpageNavigationElementRepository) bind IWebpageNavigationElementRepository::class
    singleOf(::ExposedWebpageMarkdownRepository) bind IWebpageMarkdownRepository::class
    singleOf(::ExposedPdfMarkdownRepository) bind IPdfMarkdownRepository::class
    singleOf(::ExposedQuerySessionRepository) bind IQuerySessionRepository::class
    singleOf(::ExposedUrlAccessRepository) bind IUrlAccessRepository::class
    singleOf(::ExposedSitemapCacheRepository) bind ISitemapCacheRepository::class
    singleOf(::ExposedLlmTokenUsageRepository) bind ILlmTokenUsageRepository::class
}

val infrastructureTestModule = module {
    includes(infrastructureCommonTestModule)
    single<CoroutineDispatcher> { StandardTestDispatcher() }
}

val infrastructureBenchmarkTestModule = module {
    includes(infrastructureCommonTestModule)
}
