package io.deepsearch.infrastructure.config

import io.deepsearch.domain.config.DatabaseEncryptionConfig
import io.deepsearch.domain.config.EnvironmentConfig
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.config.PostgresConfig
import io.deepsearch.domain.proxy.IProxyRuleRepository
import io.deepsearch.domain.repositories.*
import io.deepsearch.domain.services.IBatchSnapshotStorageService
import io.deepsearch.domain.services.IImageStorageService
import io.deepsearch.domain.services.ITemporaryFileStorageService
import io.deepsearch.infrastructure.database.*
import io.deepsearch.infrastructure.repositories.*
import io.deepsearch.infrastructure.services.*
import io.deepsearch.infrastructure.storage.InMemoryBatchSnapshotStorageService
import io.deepsearch.infrastructure.storage.InMemoryImageStorageService
import io.deepsearch.infrastructure.storage.InMemoryTemporaryFileStorageService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import org.koin.core.module.dsl.createdAtStart
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
    
    // Database services - createdAtStart triggers schema initialization at Koin startup
    // instead of lazily during the first test, avoiding ~4s overhead in test timing
    singleOf(::DatabaseConfigurationService) { createdAtStart() } bind IDatabaseConfigurationService::class

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
    
    // PDF source eval cache table
    singleOf(::PdfSourceEvalCacheTable)
    
    // Website context cache table (for query processing)
    singleOf(::WebsiteContextTable)
    
    // Search flow events table (for timeline visualization)
    singleOf(::SearchFlowEventsTable)
    
    // External API usage table (for cost tracking)
    singleOf(::ExternalApiUsageTable)
    
    // Visual identification cache tables
    singleOf(::HiddenContainerTableCacheTable)
    singleOf(::VisionDetectionCacheTable)

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
    
    // PDF source eval cache repository
    singleOf(::ExposedPdfSourceEvalCacheRepository) bind IPdfSourceEvalCacheRepository::class
    
    // Website context cache repository (for query processing)
    singleOf(::ExposedWebsiteContextRepository) bind IWebsiteContextRepository::class
    
    // Search flow events repository (for timeline visualization)
    singleOf(::ExposedSearchFlowEventRepository) bind ISearchFlowEventRepository::class
    
    // External API usage repository (for cost tracking)
    singleOf(::ExposedExternalApiUsageRepository) bind IExternalApiUsageRepository::class
    
    // Visual identification cache repositories
    singleOf(::ExposedHiddenContainerTableCacheRepository) bind IHiddenContainerTableCacheRepository::class
    singleOf(::ExposedVisionDetectionCacheRepository) bind IVisionDetectionCacheRepository::class
    
    // In-memory temporary file storage for testing (replaces GCS in production)
    singleOf(::InMemoryTemporaryFileStorageService) bind ITemporaryFileStorageService::class
    
    // In-memory image storage for testing (replaces GCS in production)
    singleOf(::InMemoryImageStorageService) bind IImageStorageService::class
    
    // In-memory batch snapshot storage for testing (replaces GCS in production)
    singleOf(::InMemoryBatchSnapshotStorageService) bind IBatchSnapshotStorageService::class
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
