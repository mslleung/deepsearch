package io.deepsearch.infrastructure.config

import io.deepsearch.domain.proxy.IProxyRuleRepository
import io.deepsearch.domain.repositories.*
import io.deepsearch.domain.repositories.IWebpageImageLinkageRepository
import io.deepsearch.domain.services.ITemporaryFileStorageService
import io.deepsearch.infrastructure.database.*
import io.deepsearch.infrastructure.repositories.*
import io.deepsearch.infrastructure.services.DatabaseConfigurationService
import io.deepsearch.infrastructure.services.DatabaseCryptoService
import io.deepsearch.infrastructure.services.IDatabaseConfigurationService
import io.deepsearch.infrastructure.services.IDatabaseCryptoService
import io.deepsearch.infrastructure.services.ITransactionService
import io.deepsearch.infrastructure.services.TransactionService
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
    single<ITemporaryFileStorageService> {
        val bucketName = System.getenv("GCS_TEMP_BUCKET_NAME")
            ?: throw IllegalStateException("GCS_TEMP_BUCKET_NAME environment variable not set")
        GcsTemporaryFileStorageService(bucketName, get())
    }
    
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
    
    // HTML source eval cache table
    singleOf(::HtmlSourceEvalCacheTable)
    
    // PDF source eval cache table
    singleOf(::PdfSourceEvalCacheTable)
    
    // Website context cache table (for query processing)
    singleOf(::WebsiteContextTable)
    
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
    
    // HTML source eval cache repository
    singleOf(::ExposedHtmlSourceEvalCacheRepository) bind IHtmlSourceEvalCacheRepository::class
    
    // PDF source eval cache repository
    singleOf(::ExposedPdfSourceEvalCacheRepository) bind IPdfSourceEvalCacheRepository::class
    
    // Website context cache repository (for query processing)
    singleOf(::ExposedWebsiteContextRepository) bind IWebsiteContextRepository::class

    // Request-scoped repositories (user/auth related)
    requestScope {
        scopedOf(::ExposedApiKeyRepository) bind IApiKeyRepository::class
        scopedOf(::ExposedUserRepository) bind IUserRepository::class
        scopedOf(::ExposedUserSubscriptionRepository) bind IUserSubscriptionRepository::class
        scopedOf(::ExposedQuerySessionRepository) bind IQuerySessionRepository::class
    }
}
