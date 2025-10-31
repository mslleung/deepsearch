package io.deepsearch.infrastructure.config

import io.deepsearch.domain.config.DatabaseEncryptionConfig
import io.deepsearch.domain.repositories.*
import io.deepsearch.infrastructure.database.*
import io.deepsearch.infrastructure.repositories.*
import org.koin.core.module.dsl.scopedOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.module.requestScope

val infrastructureModule = module {
    // Database encryption service
    singleOf(::DatabaseCryptoService)
    
    // All table instances (singletons, depend on DatabaseCryptoService)
    single { UserTable(get()) }
    single { ApiKeyTable(get(), get()) }
    single { RawApiKeyTable(get(), get()) }
    single { ApiKeyUsageTable(get(), get()) }
    single { UserSubscriptionTable(get(), get()) }
    single { WebpageIconTable(get()) }
    single { WebpageImageTable(get()) }
    single { WebpagePopupTable(get()) }
    single { WebpageTableTable(get()) }
    single { WebpageTableInterpretationTable(get()) }
    single { WebpageSemanticElementTable(get()) }
    single { WebpageMarkdownTable(get()) }
    single { PdfMarkdownTable(get()) }
    single { QuerySessionTable(get()) }
    single { PrecacheJobTable(get()) }
    
    // Database configuration service (depends on all tables)
    singleOf(::DatabaseConfigurationService) bind IDatabaseConfigurationService::class

    requestScope {
        scopedOf(::ExposedApiKeyRepository) bind IApiKeyRepository::class
        scopedOf(::ExposedRawApiKeyRepository) bind IRawApiKeyRepository::class
        scopedOf(::ExposedApiKeyUsageRepository) bind IApiKeyUsageRepository::class
        scopedOf(::ExposedPrecacheJobRepository) bind IPrecacheJobRepository::class
        scopedOf(::ExposedUserRepository) bind IUserRepository::class
        scopedOf(::ExposedUserSubscriptionRepository) bind IUserSubscriptionRepository::class
        scopedOf(::ExposedWebpageIconRepository) bind IWebpageIconRepository::class
        scopedOf(::ExposedWebpageImageRepository) bind IWebpageImageRepository::class
        scopedOf(::ExposedWebpagePopupRepository) bind IWebpagePopupRepository::class
        scopedOf(::ExposedWebpageTableRepository) bind IWebpageTableRepository::class
        scopedOf(::ExposedWebpageTableInterpretationRepository) bind IWebpageTableInterpretationRepository::class
        scopedOf(::ExposedWebpageNavigationElementRepository) bind IWebpageNavigationElementRepository::class
        scopedOf(::ExposedWebpageMarkdownRepository) bind IWebpageMarkdownRepository::class
        scopedOf(::ExposedPdfMarkdownRepository) bind IPdfMarkdownRepository::class
        scopedOf(::ExposedQuerySessionRepository) bind IQuerySessionRepository::class
    }
}
