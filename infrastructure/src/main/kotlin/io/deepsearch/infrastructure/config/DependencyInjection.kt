package io.deepsearch.infrastructure.config

import io.deepsearch.domain.repositories.*
import io.deepsearch.infrastructure.database.*
import io.deepsearch.infrastructure.repositories.*
import io.deepsearch.infrastructure.services.DatabaseConfigurationService
import io.deepsearch.infrastructure.services.DatabaseCryptoService
import io.deepsearch.infrastructure.services.IDatabaseConfigurationService
import io.deepsearch.infrastructure.services.IDatabaseCryptoService
import org.koin.core.module.dsl.createdAtStart
import org.koin.core.module.dsl.scopedOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.module.requestScope

val infrastructureModule = module {

    singleOf(::DatabaseConfigurationService) { createdAtStart() } bind IDatabaseConfigurationService::class
    singleOf(::DatabaseCryptoService) bind IDatabaseCryptoService::class

    // All table instances (singletons, depend on DatabaseCryptoService)
    singleOf(::UserTable)
    singleOf(::ApiKeyTable)
    singleOf(::RawApiKeyTable)
    singleOf(::ApiKeyUsageTable)
    singleOf(::UserSubscriptionTable)
    singleOf(::WebpageIconTable)
    singleOf(::WebpageImageTable)
    singleOf(::WebpagePopupTable)
    singleOf(::WebpageTableTable)
    singleOf(::WebpageTableInterpretationTable)
    singleOf(::WebpageSemanticElementTable)
    singleOf(::WebpageMarkdownTable)
    singleOf(::PdfMarkdownTable)
    singleOf(::QuerySessionTable)
    singleOf(::PrecacheJobTable)

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
