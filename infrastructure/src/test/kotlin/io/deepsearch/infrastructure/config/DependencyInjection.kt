package io.deepsearch.infrastructure.config

import io.deepsearch.domain.config.DatabaseEncryptionConfig
import io.deepsearch.domain.config.EnvironmentConfig
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.config.PostgresConfig
import io.deepsearch.domain.proxy.IProxyRuleRepository
import io.deepsearch.domain.repositories.*
import io.deepsearch.infrastructure.database.*
import io.deepsearch.infrastructure.repositories.*
import io.deepsearch.infrastructure.services.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import org.koin.core.module.dsl.scopedOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

private val infrastructureCommonTestModule = module {
    // Test configs
    single { EnvironmentConfig(isDevelopmentMode = true) }
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
    
    // Database services (no createdAtStart - lazy init allows DatabaseTables to use KoinComponent.inject())
    singleOf(::DatabaseConfigurationService) bind IDatabaseConfigurationService::class

    singleOf(::DatabaseCryptoService) bind IDatabaseCryptoService::class
    singleOf(::TransactionService) bind ITransactionService::class
    
    // DatabaseTables container (lazily resolves all tables via Koin instance, not GlobalContext)
    single { DatabaseTables(getKoin()) }

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
    singleOf(::PeriodicIndexConfigTable)
    singleOf(::SitemapCacheTable)
    singleOf(::LlmTokenUsageTable)
    singleOf(::ProxyRuleTable)
    singleOf(::BatchPeriodicIndexJobTable)
    singleOf(::BatchUrlStateTable)
    
    // Knowledge Graph tables
    singleOf(::KgEntityEmbeddingsTable)
    singleOf(::KgEntitySourcesTable)
    singleOf(::KgRelationshipSourcesTable)
    
    // Markdown indexing task table
    singleOf(::MarkdownIndexingTaskTable)
    
    // HTML source eval cache table
    singleOf(::HtmlSourceEvalCacheTable)
    
    // Website context cache table (for query processing)
    singleOf(::WebsiteContextTable)

    // Repositories as singletons in tests (no request scope needed for testing)
    singleOf(::ExposedUserRepository) bind IUserRepository::class
    singleOf(::ExposedApiKeyRepository) bind IApiKeyRepository::class
    singleOf(::ExposedUserSubscriptionRepository) bind IUserSubscriptionRepository::class
    singleOf(::ExposedWebpageIconRepository) bind IWebpageIconRepository::class
    singleOf(::ExposedWebpageImageRepository) bind IWebpageImageRepository::class
    singleOf(::ExposedWebpageImageLinkageRepository) bind IWebpageImageLinkageRepository::class
    singleOf(::ExposedWebpagePopupRepository) bind IWebpagePopupRepository::class
    singleOf(::ExposedWebpageTableRepository) bind IWebpageTableRepository::class
    singleOf(::ExposedWebpageTableInterpretationRepository) bind IWebpageTableInterpretationRepository::class
    singleOf(::ExposedWebpageNavigationElementRepository) bind IWebpageNavigationElementRepository::class
    singleOf(::ExposedWebpageMarkdownRepository) bind IWebpageMarkdownRepository::class
    singleOf(::ExposedQuerySessionRepository) bind IQuerySessionRepository::class
    singleOf(::ExposedUrlAccessRepository) bind IUrlAccessRepository::class
    singleOf(::ExposedSitemapCacheRepository) bind ISitemapCacheRepository::class
    singleOf(::ExposedLlmTokenUsageRepository) bind ILlmTokenUsageRepository::class
    singleOf(::ExposedPeriodicIndexConfigRepository) bind IPeriodicIndexConfigRepository::class
    singleOf(::ExposedPeriodicIndexJobRepository) bind IPeriodicIndexJobRepository::class
    singleOf(::ExposedProxyRuleRepository) bind IProxyRuleRepository::class
    singleOf(::ExposedBatchPeriodicIndexJobRepository) bind IBatchPeriodicIndexJobRepository::class
    singleOf(::ExposedBatchUrlStateRepository) bind IBatchUrlStateRepository::class
    
    // Knowledge Graph repository
    singleOf(::AgeKnowledgeGraphRepository) bind IKnowledgeGraphRepository::class
    
    // Markdown indexing task repository
    singleOf(::ExposedMarkdownIndexingTaskRepository) bind IMarkdownIndexingTaskRepository::class
    
    // HTML source eval cache repository
    singleOf(::ExposedHtmlSourceEvalCacheRepository) bind IHtmlSourceEvalCacheRepository::class
    
    // Website context cache repository (for query processing)
    singleOf(::ExposedWebsiteContextRepository) bind IWebsiteContextRepository::class
}

val infrastructureTestModule = module {
    includes(infrastructureCommonTestModule)
    single<CoroutineDispatcher> { StandardTestDispatcher() }
    single<IDispatcherProvider> {
        val testDispatcher = get<CoroutineDispatcher>()
        object : IDispatcherProvider {
            override val io = testDispatcher
            override val default = testDispatcher
            override val main = testDispatcher
            override val unconfined = testDispatcher
        }
    }
}

val infrastructureBenchmarkTestModule = module {
    includes(infrastructureCommonTestModule)
}
