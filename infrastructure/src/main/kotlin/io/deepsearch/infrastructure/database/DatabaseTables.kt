package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Container that lazily resolves all database tables from Koin.
 * This avoids having 20+ constructor parameters in DatabaseConfigurationService.
 *
 * Uses KoinComponent to resolve tables lazily, keeping the DI registration simple
 * (zero constructor parameters).
 */
class DatabaseTables : KoinComponent {

    val userTable: UserTable by inject()
    val apiKeyTable: ApiKeyTable by inject()
    val userSubscriptionTable: UserSubscriptionTable by inject()
    val webpageIconCacheTable: WebpageIconCacheTable by inject()
    val webpageImageCacheTable: WebpageImageCacheTable by inject()
    val webpageImageLinkageTable: WebpageImageLinkageTable by inject()
    val webpagePopupCacheTable: WebpagePopupCacheTable by inject()
    val webpageTableCacheTable: WebpageTableCacheTable by inject()
    val webpageTableInterpretationCacheTable: WebpageTableInterpretationCacheTable by inject()
    val webpageSemanticElementCacheTable: WebpageSemanticElementCacheTable by inject()
    val webpageMarkdownCacheTable: WebpageMarkdownCacheTable by inject()
    val querySessionTable: QuerySessionTable by inject()
    val periodicIndexJobTable: PeriodicIndexJobTable by inject()
    val sitemapCacheTable: SitemapCacheTable by inject()
    val urlAccessTable: UrlAccessTable by inject()
    val llmTokenUsageTable: LlmTokenUsageTable by inject()
    val periodicIndexConfigTable: PeriodicIndexConfigTable by inject()
    val batchPeriodicIndexJobTable: BatchPeriodicIndexJobTable by inject()
    val batchUrlStateTable: BatchUrlStateTable by inject()
    val proxyRuleTable: ProxyRuleTable by inject()
    val kgEntityEmbeddingsTable: KgEntityEmbeddingsTable by inject()
    val kgEntitySourcesTable: KgEntitySourcesTable by inject()
    val kgRelationshipSourcesTable: KgRelationshipSourcesTable by inject()

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
        )
    }
}

