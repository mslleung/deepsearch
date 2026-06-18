package io.deepsearch.infrastructure.config

import io.deepsearch.domain.config.GcsConfig
import io.deepsearch.domain.proxy.IProxyRuleRepository
import io.deepsearch.domain.repositories.*
import io.deepsearch.domain.repositories.IWebpageImageLinkageRepository
import io.deepsearch.domain.services.IBatchSnapshotStorageService
import io.deepsearch.domain.services.IImageStorageService
import io.deepsearch.domain.services.IIterationScreenshotStorage
import io.deepsearch.domain.services.ITemporaryFileStorageService
import io.deepsearch.infrastructure.database.*
import io.deepsearch.infrastructure.repositories.*
import io.deepsearch.infrastructure.services.DatabaseConfigurationService
import io.deepsearch.infrastructure.services.DatabaseCryptoService
import io.deepsearch.infrastructure.services.IDatabaseConfigurationService
import io.deepsearch.infrastructure.services.IDatabaseCryptoService
import io.deepsearch.infrastructure.services.ITransactionService
import io.deepsearch.infrastructure.services.TransactionService
import io.deepsearch.infrastructure.storage.GcsBatchSnapshotStorageService
import io.deepsearch.infrastructure.storage.GcsImageStorageService
import io.deepsearch.infrastructure.storage.GcsIterationScreenshotStorage
import io.deepsearch.infrastructure.storage.GcsTemporaryFileStorageService
import org.koin.core.module.dsl.scopedOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.module.requestScope

val infrastructureModule = module {

    // Database services (no createdAtStart - lazy init allows DatabaseTables to use KoinComponent.inject())
    singleOf(::DatabaseConfigurationService) bind IDatabaseConfigurationService::class
    singleOf(::DatabaseCryptoService) bind IDatabaseCryptoService::class
    singleOf(::TransactionService) bind ITransactionService::class
    
    // Google Cloud Storage for temporary file storage during batch processing
    // Uses GCS Free Tier: 5GB regional storage (US regions)
    singleOf(::GcsTemporaryFileStorageService) bind ITemporaryFileStorageService::class
    
    // Google Cloud Storage for permanent image storage
    // Images are stored with content-based hashes for deduplication
    // Clients fetch images via signed URLs (no base64 overhead)
    singleOf(::GcsImageStorageService) bind IImageStorageService::class
    
    // Google Cloud Storage for batch snapshot data
    // Stores intermediate batch processing data (HTML, screenshots, icons, images)
    // Uses temp bucket with lifecycle policy for automatic cleanup
    singleOf(::GcsBatchSnapshotStorageService) bind IBatchSnapshotStorageService::class
    
    // Google Cloud Storage for agentic navigation iteration screenshots
    // Stores raw, annotated, and region-crop screenshots per iteration for debugging
    singleOf(::GcsIterationScreenshotStorage) bind IIterationScreenshotStorage::class
    
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
    singleOf(::SitemapCacheTable)
    singleOf(::LlmTokenUsageTable)
    singleOf(::PeriodicIndexConfigTable)
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
    
    // Agentic navigation iteration tables (for debugging session replay)
    singleOf(::AgenticNavIterationTable)
    singleOf(::AgenticNavScreenshotTable)
    
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
    singleOf(::ExposedBatchPeriodicIndexJobRepository) bind IBatchPeriodicIndexJobRepository::class
    singleOf(::ExposedBatchUrlStateRepository) bind IBatchUrlStateRepository::class
    singleOf(::ExposedProxyRuleRepository) bind IProxyRuleRepository::class
    
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
    
    // Agentic navigation iteration repository (for debugging session replay)
    singleOf(::ExposedAgenticNavIterationRepository) bind IAgenticNavIterationRepository::class

    // Request-scoped repositories (user/auth related)
    requestScope {
        scopedOf(::ExposedApiKeyRepository) bind IApiKeyRepository::class
        scopedOf(::ExposedUserRepository) bind IUserRepository::class
        scopedOf(::ExposedUserSubscriptionRepository) bind IUserSubscriptionRepository::class
        scopedOf(::ExposedQuerySessionRepository) bind IQuerySessionRepository::class
    }
}
