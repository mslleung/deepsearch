package io.deepsearch.infrastructure.config

import io.deepsearch.domain.proxy.IProxyRuleRepository
import io.deepsearch.domain.repositories.*
import io.deepsearch.domain.repositories.IWebpageImageLinkageRepository
import io.deepsearch.infrastructure.database.*
import io.deepsearch.infrastructure.repositories.*
import io.deepsearch.infrastructure.services.DatabaseConfigurationService
import io.deepsearch.infrastructure.services.DatabaseCryptoService
import io.deepsearch.infrastructure.services.IDatabaseConfigurationService
import io.deepsearch.infrastructure.services.IDatabaseCryptoService
import io.deepsearch.infrastructure.services.ITransactionService
import io.deepsearch.infrastructure.services.TransactionService
import org.koin.core.module.dsl.createdAtStart
import org.koin.core.module.dsl.scopedOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.module.requestScope

val infrastructureModule = module {

    singleOf(::DatabaseConfigurationService) { createdAtStart() } bind IDatabaseConfigurationService::class
    singleOf(::DatabaseCryptoService) bind IDatabaseCryptoService::class
    singleOf(::TransactionService) bind ITransactionService::class

    // All table instances (singletons, depend on DatabaseCryptoService)
    singleOf(::UserTable)
    singleOf(::ApiKeyTable)
    singleOf(::UserSubscriptionTable)
    singleOf(::WebpageIconCacheTable)
    singleOf(::WebpageImageCacheTable)
    singleOf(::WebpageImageLinkageTable)
    singleOf(::WebpagePopupCacheTable)
    singleOf(::WebpageTableCacheTable)
    singleOf(::WebpageTableInterpretationCacheTable)
    singleOf(::WebpageSemanticElementCacheTable)
    singleOf(::WebpageMarkdownCacheTable)
    singleOf(::QuerySessionTable)
    singleOf(::UrlAccessTable)
    singleOf(::PeriodicIndexJobTable)
    singleOf(::SitemapCacheTable)
    singleOf(::LlmTokenUsageTable)
    singleOf(::PeriodicIndexConfigTable)
    singleOf(::ProxyRuleTable)
    
    // Singleton repositories (stateless, used by singleton services)
    singleOf(::ExposedWebpageIconRepository) bind IWebpageIconRepository::class
    singleOf(::ExposedWebpageImageRepository) bind IWebpageImageRepository::class
    singleOf(::ExposedWebpageImageLinkageRepository) bind IWebpageImageLinkageRepository::class
    singleOf(::ExposedWebpagePopupRepository) bind IWebpagePopupRepository::class
    singleOf(::ExposedWebpageTableRepository) bind IWebpageTableRepository::class
    singleOf(::ExposedWebpageTableInterpretationRepository) bind IWebpageTableInterpretationRepository::class
    singleOf(::ExposedWebpageNavigationElementRepository) bind IWebpageNavigationElementRepository::class
    singleOf(::ExposedWebpageMarkdownRepository) bind IWebpageMarkdownRepository::class
    singleOf(::ExposedSitemapCacheRepository) bind ISitemapCacheRepository::class
    singleOf(::ExposedLlmTokenUsageRepository) bind ILlmTokenUsageRepository::class
    singleOf(::ExposedPeriodicIndexConfigRepository) bind IPeriodicIndexConfigRepository::class
    singleOf(::ExposedPeriodicIndexJobRepository) bind IPeriodicIndexJobRepository::class
    singleOf(::ExposedUrlAccessRepository) bind IUrlAccessRepository::class

    // Request-scoped repositories (user/auth related)
    requestScope {
        scopedOf(::ExposedApiKeyRepository) bind IApiKeyRepository::class
        scopedOf(::ExposedUserRepository) bind IUserRepository::class
        scopedOf(::ExposedUserSubscriptionRepository) bind IUserSubscriptionRepository::class
        scopedOf(::ExposedQuerySessionRepository) bind IQuerySessionRepository::class
        scopedOf(::ExposedProxyRuleRepository) bind IProxyRuleRepository::class
    }
}
