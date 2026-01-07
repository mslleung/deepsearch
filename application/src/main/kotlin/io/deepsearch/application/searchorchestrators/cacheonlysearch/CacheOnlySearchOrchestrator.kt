package io.deepsearch.application.searchorchestrators.cacheonlysearch

import io.deepsearch.application.searchorchestrators.ISearchOrchestrator
import io.deepsearch.application.services.FileQueryResult
import io.deepsearch.application.services.IFileSearchService
import io.deepsearch.application.services.ILlmTokenUsageService
import io.deepsearch.application.services.IQuerySessionService
import io.deepsearch.application.services.IKgHybridRetrievalService
import io.deepsearch.application.services.IUrlAccessService
import io.deepsearch.application.services.SearchEvent
import io.deepsearch.application.services.WebpageCacheService
import io.deepsearch.domain.agents.IMarkdownSourceEvalAgent
import io.deepsearch.domain.agents.IStreamingAnswerSynthesisAgent
import io.deepsearch.domain.agents.MarkdownSourceEvalInput
import io.deepsearch.domain.agents.StreamingAnswerSynthesisInput
import io.deepsearch.domain.agents.StreamingAnswerStreamItem
import io.deepsearch.domain.models.valueobjects.EvaluatedSource
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import io.deepsearch.domain.models.entities.WebpageMarkdown
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.CachedUrlAccess
import io.deepsearch.domain.models.valueobjects.MarkdownSource
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.SearchMode
import io.deepsearch.domain.proxy.ProxyConfiguration
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.knowledgegraph.KgHybridRetrievalResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URI
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

interface ICacheOnlySearchOrchestrator : ISearchOrchestrator

/**
 * Orchestrates cache-only search using pre-indexed data.
 * 
 * Searches both:
 * - Cached HTML pages (via hybrid search)
 * - File search stores (for PDFs, documents, etc.)
 * 
 * Both searches are performed in parallel for optimal performance.
 * 
 * Returns a Flow<SearchEvent> that emits session start and completion events.
 */
@OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)
class CacheOnlySearchOrchestrator(
    private val webpageCacheService: WebpageCacheService,
    private val fileSearchService: IFileSearchService,
    private val kgHybridRetrievalService: IKgHybridRetrievalService,
    private val markdownSourceEvalAgent: IMarkdownSourceEvalAgent,
    private val streamingAnswerSynthesisAgent: IStreamingAnswerSynthesisAgent,
    private val querySessionService: IQuerySessionService,
    private val urlAccessService: IUrlAccessService,
    private val tokenUsageService: ILlmTokenUsageService
) : ICacheOnlySearchOrchestrator {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override fun execute(
        searchQuery: SearchQuery,
        maxCacheAge: Long?,
        apiKeyId: ApiKeyId,
        proxyConfig: ProxyConfiguration
    ): Flow<SearchEvent> = flow {
        val session = querySessionService.createSession(
            searchQuery.query,
            searchQuery.url,
            apiKeyId,
            SearchMode.CACHE_ONLY
        )
        val sessionId = session.id

        // Emit session created
        emit(
            SearchEvent.SessionCreated(
                sessionId = sessionId,
                query = searchQuery.query,
                url = searchQuery.url,
                mode = "cache-only"
            )
        )

        try {
            logger.debug("[{}] Executing cache-only search for query: {}", sessionId.value, searchQuery.query)

            // Step 1: Perform hybrid search, file search, and KG retrieval in parallel
            val domain = extractDomain(searchQuery.url)
            
            val (cachedWebpages, fileSearchResult, kgResult) = coroutineScope {
                val hybridSearchDeferred = async {
                    webpageCacheService.searchHybrid(
                        query = searchQuery.query,
                        baseUrl = searchQuery.url,
                        maxCacheAge = maxCacheAge,
                        limit = 20,
                        sessionId = sessionId
                    )
                }
                
                val fileSearchDeferred = async {
                    queryFileSearch(domain, searchQuery.query, sessionId, maxCacheAge)
                }
                
                val kgSearchDeferred = async {
                    try {
                        if (kgHybridRetrievalService.hasDataForUrlPrefix(searchQuery.url)) {
                            kgHybridRetrievalService.retrieve(
                                query = searchQuery.query,
                                baseUrl = searchQuery.url,
                                maxCacheAge = maxCacheAge,
                                sessionId = sessionId
                            )
                        } else null
                    } catch (e: Exception) {
                        logger.warn("[{}] KG search failed: {}", sessionId.value, e.message)
                        null
                    }
                }
                
                Triple(hybridSearchDeferred.await(), fileSearchDeferred.await(), kgSearchDeferred.await())
            }

            logger.debug(
                "[{}] Parallel search complete: {} cached webpages, file search: {}, KG: {}",
                sessionId.value, 
                cachedWebpages.size, 
                if (fileSearchResult != null) "found" else "none",
                if (kgResult?.hasResults() == true) "found" else "none"
            )

            // Filter valid webpages with markdown content
            val validWebpages = cachedWebpages.filter { !it.markdown.isNullOrBlank() }

            // Check if we have any content (webpages or file search)
            if (validWebpages.isEmpty() && (fileSearchResult == null || fileSearchResult.markdown.isBlank())) {
                logger.info("[{}] No cached content found for query", sessionId.value)
                querySessionService.completeSessionLinksExhausted(sessionId, "No relevant content found in cache.", answerFound = false)

                val sessionDetail = querySessionService.getSessionDetailInternal(sessionId)
                emit(
                    SearchEvent.SessionCompleted(
                        sessionId = sessionId,
                        finishReason = "LINKS_EXHAUSTED",
                        sessionDetail = sessionDetail
                    )
                )
                return@flow
            }

            // Record URL accesses and emit URL processed events for webpages
            emitWebpageProcessedEvents(validWebpages, sessionId)

            // Emit file search processed event if we have results
            if (fileSearchResult != null && fileSearchResult.markdown.isNotBlank()) {
                fileSearchResult.citations.forEach { citation ->
                    emit(
                        SearchEvent.UrlProcessed(
                            sessionId = sessionId,
                            url = citation.sourceUrl,
                            accessType = "FILE_SEARCH",
                            title = "File Search Results",
                            description = "Documents from $domain",
                            markdownLength = fileSearchResult.markdown.length
                        )
                    )
                }
            }

            // Combine all markdown sources (including KG facts)
            val markdownSources = buildMarkdownSources(validWebpages, fileSearchResult, kgResult, domain)

            // Step 2: Evaluate each markdown source in parallel
            // Cache-only search uses raw query as expandedQuery (no query processing)
            val evaluatedSources: List<EvaluatedSource> = markdownSources.asFlow()
                .flatMapMerge(concurrency = 100) { source ->
                    flow {
                        val output = markdownSourceEvalAgent.generate(
                            MarkdownSourceEvalInput(
                                markdownSource = source,
                                expandedQuery = searchQuery.query
                            )
                        )
                        
                        tokenUsageService.recordTokenUsage(
                            sessionId, "MarkdownSourceEvalAgent",
                            output.tokenUsage.modelName,
                            output.tokenUsage.promptTokens,
                            output.tokenUsage.outputTokens,
                            output.tokenUsage.totalTokens
                        )
                        
                        emit(output.evaluatedSource)
                    }
                }
                .mapNotNull { it }
                .toList()

            logger.debug(
                "[{}] Evaluated {} sources, {} relevant",
                sessionId.value, markdownSources.size, evaluatedSources.size
            )

            emit(
                SearchEvent.SourcesEvaluated(
                    sessionId = sessionId,
                    processedUrlCount = markdownSources.size,
                    relevantCount = evaluatedSources.size,
                    isGoodEnough = evaluatedSources.isNotEmpty(),
                    reason = if (evaluatedSources.isNotEmpty()) "Sources evaluated" else "No relevant sources found"
                )
            )

            // Step 3: Synthesize answer with streaming
            var fullAnswer = ""
            var answerFound = false
            var imageIds = emptyList<String>()
            var citedSourceUrls = emptyList<String>()
            
            streamingAnswerSynthesisAgent.generateStream(
                StreamingAnswerSynthesisInput(
                    query = searchQuery.query,
                    evaluatedSources = evaluatedSources,
                    previouslySearchedQueries = emptyList()
                )
            ).collect { item ->
                when (item) {
                    is StreamingAnswerStreamItem.Chunk -> {
                        fullAnswer += item.text
                        emit(SearchEvent.AnswerChunk(sessionId, item.text))
                    }
                    is StreamingAnswerStreamItem.Complete -> {
                        // Record token usage from the final streaming chunk
                        tokenUsageService.recordTokenUsage(
                            sessionId, "StreamingAnswerSynthesisAgent",
                            item.tokenUsage.modelName, item.tokenUsage.promptTokens,
                            item.tokenUsage.outputTokens, item.tokenUsage.totalTokens
                        )
                        // For cache-only search, we don't use feedback loop - just check status
                        answerFound = item.status == io.deepsearch.domain.models.valueobjects.AnswerStatus.FINISH_SEARCH
                        imageIds = item.imageIds
                        citedSourceUrls = item.citedSourceUrls
                    }
                }
            }

            // Mark answer sources - prefer cited sources if available
            val answerSources = citedSourceUrls.ifEmpty { evaluatedSources.map { it.url } }
            if (answerSources.isNotEmpty()) {
                urlAccessService.markUrlsAsUsedInAnswer(sessionId, answerSources)
            }

            // Step 4: Complete session
            querySessionService.completeSessionAnswerComplete(sessionId, fullAnswer, answerFound, imageIds)

            val sessionDetail = querySessionService.getSessionDetailInternal(sessionId)
            emit(
                SearchEvent.SessionCompleted(
                    sessionId = sessionId,
                    finishReason = "ANSWER_COMPLETE",
                    sessionDetail = sessionDetail,
                    imageIds = imageIds
                )
            )

        } catch (e: Exception) {
            logger.error("[{}] Error in cache-only search: {}", sessionId.value, e.message, e)
            querySessionService.hardTimeout(sessionId, e.message ?: "Unknown error")
            emit(
                SearchEvent.SessionError(
                    sessionId = sessionId,
                    errorType = e::class.simpleName ?: "Unknown",
                    errorMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    /**
     * Query file search store for a domain, returning null if no store exists.
     */
    private suspend fun queryFileSearch(
        domain: String,
        query: String,
        sessionId: QuerySessionId,
        maxCacheAge: Long?
    ): FileQueryResult? {
        return try {
            fileSearchService.query(domain, query, sessionId, maxCacheAge)
        } catch (e: IllegalStateException) {
            // No file search store for this domain - this is expected
            logger.debug("[{}] No file search store for domain {}: {}", sessionId.value, domain, e.message)
            null
        } catch (e: Exception) {
            logger.warn("[{}] File search failed for domain {}: {}", sessionId.value, domain, e.message)
            null
        }
    }

    /**
     * Emit UrlProcessed events and record URL accesses for cached webpages.
     */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<SearchEvent>.emitWebpageProcessedEvents(
        webpages: List<WebpageMarkdown>,
        sessionId: QuerySessionId
    ) {
        webpages.forEach { webpage ->
            urlAccessService.recordUrlAccess(sessionId, CachedUrlAccess(webpage.url, Clock.System.now()))
            emit(
                SearchEvent.UrlProcessed(
                    sessionId = sessionId,
                    url = webpage.url,
                    accessType = "CACHED",
                    title = webpage.title,
                    description = webpage.description,
                    markdownLength = webpage.markdown?.length
                )
            )
        }
    }

    /**
     * Build combined markdown sources from webpages and file search results.
     * 
     * Note: KG facts are intentionally NOT included here. While we query the KG,
     * we don't use its facts directly because:
     * 1. KG facts lack authority classification (OFFICIAL_LIVING_DOC, etc.)
     * 2. They would duplicate content already in cached webpages
     * 3. The synthetic "knowledge-graph://" URL can't be properly classified
     * 
     * The eval agent properly classifies sources from real URLs, handling
     * staleness and conflicts appropriately.
     */
    private fun buildMarkdownSources(
        webpages: List<WebpageMarkdown>,
        fileSearchResult: FileQueryResult?,
        @Suppress("UNUSED_PARAMETER") kgResult: KgHybridRetrievalResult?,
        domain: String
    ): List<MarkdownSource> {
        return buildList {
            // Add webpage sources
            webpages.forEach { webpage ->
                add(MarkdownSource(webpage.url, webpage.title, webpage.description, webpage.markdown!!))
            }
            
            // Add file search results if available
            if (fileSearchResult != null && fileSearchResult.markdown.isNotBlank()) {
                add(
                    MarkdownSource(
                        url = fileSearchResult.citations[0].sourceUrl,
                        title = "File Search Results",
                        description = "Documents from $domain",
                        markdown = fileSearchResult.markdown
                    )
                )
            }
            
            // KG facts are intentionally not included - see note above
        }
    }

    /**
     * Extract domain from URL.
     */
    private fun extractDomain(url: String): String {
        return try {
            val uri = URI(url)
            uri.host?.lowercase() ?: url
        } catch (e: Exception) {
            logger.warn("Failed to extract domain from URL: {}", url)
            url
        }
    }
}
