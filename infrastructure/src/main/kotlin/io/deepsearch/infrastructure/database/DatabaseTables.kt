package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table
import org.koin.core.Koin

/**
 * Container that lazily resolves all database tables from Koin.
 * This avoids having 20+ constructor parameters in DatabaseConfigurationService.
 *
 * Takes a Koin instance explicitly instead of using GlobalContext via KoinComponent,
 * which allows it to work during eager initialization (createdAtStart) when
 * GlobalContext is not yet available.
 */
class DatabaseTables(private val koin: Koin) {

    val userTable: UserTable by lazy { koin.get() }
    val apiKeyTable: ApiKeyTable by lazy { koin.get() }
    val userSubscriptionTable: UserSubscriptionTable by lazy { koin.get() }
    val webpageIconCacheTable: WebpageIconCacheTable by lazy { koin.get() }
    val webpageImageCacheTable: WebpageImageCacheTable by lazy { koin.get() }
    val webpageImageLinkageTable: WebpageImageLinkageTable by lazy { koin.get() }
    val webpagePopupCacheTable: WebpagePopupCacheTable by lazy { koin.get() }
    val webpageTableCacheTable: WebpageTableCacheTable by lazy { koin.get() }
    val webpageTableInterpretationCacheTable: WebpageTableInterpretationCacheTable by lazy { koin.get() }
    val webpageSemanticElementCacheTable: WebpageSemanticElementCacheTable by lazy { koin.get() }
    val webpageMarkdownCacheTable: WebpageMarkdownCacheTable by lazy { koin.get() }
    val querySessionTable: QuerySessionTable by lazy { koin.get() }
    val periodicIndexJobTable: PeriodicIndexJobTable by lazy { koin.get() }
    val sitemapCacheTable: SitemapCacheTable by lazy { koin.get() }
    val urlAccessTable: UrlAccessTable by lazy { koin.get() }
    val llmTokenUsageTable: LlmTokenUsageTable by lazy { koin.get() }
    val periodicIndexConfigTable: PeriodicIndexConfigTable by lazy { koin.get() }
    val batchPeriodicIndexJobTable: BatchPeriodicIndexJobTable by lazy { koin.get() }
    val batchUrlStateTable: BatchUrlStateTable by lazy { koin.get() }
    val proxyRuleTable: ProxyRuleTable by lazy { koin.get() }
    val kgEntityEmbeddingsTable: KgEntityEmbeddingsTable by lazy { koin.get() }
    val kgEntitySourcesTable: KgEntitySourcesTable by lazy { koin.get() }
    val kgRelationshipSourcesTable: KgRelationshipSourcesTable by lazy { koin.get() }
    val markdownIndexingTaskTable: MarkdownIndexingTaskTable by lazy { koin.get() }
    val pdfSourceEvalCacheTable: PdfSourceEvalCacheTable by lazy { koin.get() }
    val websiteContextTable: WebsiteContextTable by lazy { koin.get() }
    val searchFlowEventsTable: SearchFlowEventsTable by lazy { koin.get() }
    val externalApiUsageTable: ExternalApiUsageTable by lazy { koin.get() }
    val hiddenContainerTableCacheTable: HiddenContainerTableCacheTable by lazy { koin.get() }
    val visionDetectionCacheTable: VisionDetectionCacheTable by lazy { koin.get() }

    /**
     * All tables as an array for schema operations (e.g., SchemaUtils.create).
     */
    val allTables: Array<Table> by lazy {
        arrayOf(
            userTable,
            apiKeyTable,
            userSubscriptionTable,
            webpageIconCacheTable,
            webpageImageCacheTable,
            webpageImageLinkageTable,
            webpagePopupCacheTable,
            webpageTableCacheTable,
            webpageTableInterpretationCacheTable,
            webpageSemanticElementCacheTable,
            webpageMarkdownCacheTable,
            querySessionTable,
            periodicIndexJobTable,
            sitemapCacheTable,
            urlAccessTable,
            llmTokenUsageTable,
            periodicIndexConfigTable,
            batchPeriodicIndexJobTable,
            batchUrlStateTable,
            proxyRuleTable,
            kgEntityEmbeddingsTable,
            kgEntitySourcesTable,
            kgRelationshipSourcesTable,
            markdownIndexingTaskTable,
            pdfSourceEvalCacheTable,
            websiteContextTable,
            searchFlowEventsTable,
            externalApiUsageTable,
            hiddenContainerTableCacheTable,
            visionDetectionCacheTable,
        )
    }
}
