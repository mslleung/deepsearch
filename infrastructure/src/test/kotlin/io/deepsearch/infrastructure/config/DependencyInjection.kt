package io.deepsearch.infrastructure.config

import io.deepsearch.domain.config.DatabaseEncryptionConfig
import io.deepsearch.domain.repositories.*
import io.deepsearch.infrastructure.database.*
import io.deepsearch.infrastructure.repositories.*
import io.deepsearch.infrastructure.services.DatabaseConfigurationService
import io.deepsearch.infrastructure.services.DatabaseCryptoService
import io.deepsearch.infrastructure.services.IDatabaseConfigurationService
import io.deepsearch.infrastructure.services.IDatabaseCryptoService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

private val infrastructureCommonTestModule = module {
    // Test encryption config
    single {
        DatabaseEncryptionConfig(
            encryptionSecret = "test-encryption-secret-32bytes!!"
        )
    }
    
    // Database encryption service
    singleOf(::DatabaseConfigurationService) bind IDatabaseConfigurationService::class
    singleOf(::DatabaseCryptoService) bind IDatabaseCryptoService::class

    // All table instances (singletons, depend on DatabaseCryptoService)
    singleOf(::UserTable)
    singleOf(::ApiKeyTable)
    singleOf(::RawApiKeyTable)
    singleOf(::ApiKeyUsageTable)
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
    singleOf(::PrecacheJobTable)
    singleOf(::SitemapCacheTable)
    
    // Repositories as singletons in tests (no request scope needed for testing)
    singleOf(::ExposedUserRepository) bind IUserRepository::class
    singleOf(::ExposedRawApiKeyRepository) bind IRawApiKeyRepository::class
    singleOf(::ExposedWebpageIconRepository) bind IWebpageIconRepository::class
    singleOf(::ExposedWebpageImageRepository) bind IWebpageImageRepository::class
    singleOf(::ExposedWebpagePopupRepository) bind IWebpagePopupRepository::class
    singleOf(::ExposedWebpageTableRepository) bind IWebpageTableRepository::class
    singleOf(::ExposedWebpageTableInterpretationRepository) bind IWebpageTableInterpretationRepository::class
    singleOf(::ExposedWebpageNavigationElementRepository) bind IWebpageNavigationElementRepository::class
    singleOf(::ExposedWebpageMarkdownRepository) bind IWebpageMarkdownRepository::class
    singleOf(::ExposedPdfMarkdownRepository) bind IPdfMarkdownRepository::class
    singleOf(::ExposedQuerySessionRepository) bind IQuerySessionRepository::class
    singleOf(::ExposedSitemapCacheRepository) bind ISitemapCacheRepository::class
}

val infrastructureTestModule = module {
    includes(infrastructureCommonTestModule)
    single<CoroutineDispatcher> { StandardTestDispatcher() }
}

val infrastructureBenchmarkTestModule = module {
    includes(infrastructureCommonTestModule)
}
