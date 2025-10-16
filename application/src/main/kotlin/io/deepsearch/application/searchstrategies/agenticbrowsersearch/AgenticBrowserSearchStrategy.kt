package io.deepsearch.application.searchstrategies.agenticbrowsersearch

import io.deepsearch.application.searchstrategies.ISearchStrategy
import io.deepsearch.application.services.INormalizeUrlService
import io.deepsearch.application.services.IWebpageExtractionService
import io.deepsearch.application.services.IWebpageLinkDiscoveryService
import io.deepsearch.domain.agents.*
import io.deepsearch.domain.browser.IBrowser
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.DispatcherProvider
import io.deepsearch.domain.models.entities.WebpageMarkdown
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult
import io.deepsearch.domain.models.valueobjects.WebpageLink
import io.deepsearch.domain.repositories.IWebpageMarkdownRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

interface IAgenticBrowserSearchStrategy : ISearchStrategy {
    override suspend fun execute(searchQuery: SearchQuery): SearchResult
}

/**
 * Agentic search that recursively discovers and extracts relevant pages in parallel.
 * Uses Google search once on the initial URL, then uses on-page link analysis
 * to recursively traverse the website. Each page is processed in parallel using
 * separate browser contexts for maximum concurrency.
 */
class AgenticBrowserSearchStrategy(
    private val browserPool: IBrowserPool,
    private val webpageExtractionService: IWebpageExtractionService,
    private val webpageLinkDiscoveryService: IWebpageLinkDiscoveryService,
    private val normalizeUrlService: INormalizeUrlService,
    private val generateAnswerAgent: IGenerateAnswerAgent,
    private val queryExpansionAgent: IQueryExpansionAgent,
    private val aggregateSearchResultsAgent: IAggregateSearchResultsAgent,
    private val webpageMarkdownRepository: IWebpageMarkdownRepository,
    private val dispatchers: DispatcherProvider
) : IAgenticBrowserSearchStrategy {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    
    companion object {
        private const val CACHE_TTL_MS = 7 * 24 * 60 * 60 * 1000L // 7 days
    }

    /**
     * Result of processing a single URL: extracted markdown and discovered links.
     */
    private data class UrlProcessingResult(
        val url: String,
        val markdown: String,
        val discoveredLinks: List<WebpageLink>
    )

    /**
     * Result of Step 1: Google link discovery and initial URL processing.
     */
    private data class Step1Result(
        val googleLinks: List<WebpageLink>,
        val initialResult: UrlProcessingResult
    )

    override suspend fun execute(searchQuery: SearchQuery): SearchResult {
        // Expand the query into multiple sub-queries
        logger.info("Expanding query: {}", searchQuery.query)
        val expansionOutput = queryExpansionAgent.generate(QueryExpansionAgentInput(searchQuery))
        val expandedQueries = expansionOutput.expandedQueries
        
        logger.info("Query expanded into {} sub-queries", expandedQueries.size)
        
        // If only one query, execute directly without aggregation
        if (expandedQueries.size == 1) {
            logger.info("Single query after expansion, executing directly")
            return executeSearchForQuery(expandedQueries[0])
        }
        
        // Execute all expanded queries in parallel
        logger.info("Executing {} queries in parallel", expandedQueries.size)
        val searchResults = withContext(dispatchers.io) {
            expandedQueries.map { query ->
                async {
                    executeSearchForQuery(query)
                }
            }.awaitAll()
        }
        
        // Aggregate results from all sub-queries
        logger.info("Aggregating results from {} sub-queries", searchResults.size)
        val aggregationOutput = aggregateSearchResultsAgent.generate(
            AggregateSearchResultsInput(
                searchQuery = searchQuery,
                searchResults = searchResults
            )
        )
        
        return aggregationOutput.aggregatedResult
    }
    
    /**
     * Execute the full search workflow for a single query.
     */
    private suspend fun executeSearchForQuery(searchQuery: SearchQuery): SearchResult {
        val browser = browserPool.acquireBrowser()
        try {
            logger.debug("Executing search for query: {}", searchQuery.query)
            
            // Step 1: Run Google link discovery and processUrl on input URL in parallel
            val step1Result = step1ProcessInitialUrl(searchQuery, browser)

            logger.debug(
                "Step 1 complete for '{}': Extracted {} chars from initial URL, discovered {} Google links + {} on-page links",
                searchQuery.query,
                step1Result.initialResult.markdown.length,
                step1Result.googleLinks.size,
                step1Result.initialResult.discoveredLinks.size
            )

            // Step 2: Process all discovered links recursively, fanning out
            // Normalize initial URL for deduplication tracking
            val initialNormalizedUrl = normalizeUrlService.normalize(searchQuery.url) ?: searchQuery.url
            val allResults = step2ProcessLinksRecursively(
                initialLinks = step1Result.googleLinks + step1Result.initialResult.discoveredLinks,
                processedNormalizedUrls = setOf(initialNormalizedUrl),
                searchQuery = searchQuery,
                browser = browser
            )

            // Step 3: Collect all markdowns and generate answer
            val allUrls = listOfNotNull(step1Result.initialResult.url) + allResults.map { it.url }
            val allMarkdowns = listOfNotNull(step1Result.initialResult.markdown) + allResults.map { it.markdown }
            return step3GenerateAnswer(searchQuery, allUrls, allMarkdowns)
        } catch (e: Exception) {
            logger.error("Error in executeSearchForQuery for '{}': {}", searchQuery.query, e.message)
            throw e
        } finally {
            browser.close()
        }
    }

    /**
     * Step 1: Run Google link discovery and processUrl on the input URL in parallel.
     * Returns discovered Google links and the result from processing the input URL.
     */
    private suspend fun step1ProcessInitialUrl(
        searchQuery: SearchQuery,
        browser: IBrowser
    ): Step1Result = withContext(dispatchers.io) {
        logger.debug("Step 1: Processing initial URL {} with Google search", searchQuery.url)

        val googleLinksDeferred = async {
            webpageLinkDiscoveryService.discoverRelevantLinksByGoogleSearch(searchQuery)
        }
        val initialProcessingDeferred = async {
            processUrl(searchQuery.url, searchQuery.query, browser)
        }

        Step1Result(
            googleLinks = googleLinksDeferred.await(),
            initialResult = initialProcessingDeferred.await()
        )
    }

    /**
     * Step 2: Process discovered links recursively in parallel waves (breadth-first).
     * Returns all processing results from all waves.
     * Uses normalized URLs for deduplication, but navigates to original URLs.
     */
    private suspend fun step2ProcessLinksRecursively(
        initialLinks: List<WebpageLink>,
        processedNormalizedUrls: Set<String>,
        searchQuery: SearchQuery,
        browser: IBrowser
    ): List<UrlProcessingResult> = withContext(dispatchers.io) {
        val allResults = mutableListOf<UrlProcessingResult>()
        var visitedNormalizedUrls = processedNormalizedUrls
        var currentBatch = initialLinks
            .distinctBy { normalizeUrlService.normalize(it.url) ?: it.url }
            .filterNot {
                val normalized = normalizeUrlService.normalize(it.url) ?: it.url
                normalized in visitedNormalizedUrls
            }

        var waveNumber = 1
        while (currentBatch.isNotEmpty()) {
            logger.debug("Step 2 Wave {}: Processing batch of {} links", waveNumber, currentBatch.size)

            // Process all URLs in this batch in parallel
            val batchResults = currentBatch.map { link ->
                async {
                    processUrl(link.url, searchQuery.query, browser)
                }
            }.awaitAll()

            // Track visited URLs using normalized form
            val batchNormalizedUrls = batchResults.map {
                normalizeUrlService.normalize(it.url) ?: it.url
            }
            visitedNormalizedUrls = visitedNormalizedUrls + batchNormalizedUrls
            allResults.addAll(batchResults)

            // Collect new links for next wave
            val newLinks = batchResults
                .flatMap { it.discoveredLinks }
                .distinctBy { normalizeUrlService.normalize(it.url) ?: it.url }
                .filterNot {
                    val normalized = normalizeUrlService.normalize(it.url) ?: it.url
                    normalized in visitedNormalizedUrls
                }

            logger.debug(
                "Wave {} complete: {} pages processed, {} markdowns extracted, {} new links discovered",
                waveNumber,
                batchResults.size,
                batchResults.count(),
                newLinks.size
            )

            currentBatch = newLinks
            waveNumber++
        }

        allResults
    }

    /**
     * Step 3: Generate answer from all markdowns using GenerateAnswerAgent.
     */
    private suspend fun step3GenerateAnswer(
        searchQuery: SearchQuery,
        allUrls: List<String>,
        allMarkdowns: List<String>
    ): SearchResult {
        val aggregatedContent = allMarkdowns.joinToString("\n\n---\n\n")

        logger.info(
            "Step 3: Generating answer from {} pages, total content: {} chars",
            allUrls.size,
            aggregatedContent.length
        )

        // Generate answer using the agent
        val answerOutput = generateAnswerAgent.generate(
            GenerateAnswerInput(
                query = searchQuery.query,
                markdowns = aggregatedContent
            )
        )

        logger.info(
            "Step 3 complete: Generated answer ({} chars) from {} pages",
            answerOutput.answer.length,
            allUrls.size
        )

        return SearchResult(
            originalQuery = searchQuery,
            answer = answerOutput.answer,
            content = aggregatedContent,
            sources = allUrls
        )
    }

    /**
     * Pure function that processes a single URL: extracts markdown and discovers links in parallel.
     * Uses cache to avoid repeated navigation to the same URLs.
     * Does not mutate any state - returns results as data.
     */
    private suspend fun processUrl(
        url: String,
        query: String,
        browser: IBrowser
    ): UrlProcessingResult = coroutineScope {
        logger.debug("Processing URL: {}", url)
        
        // Normalize URL for cache lookup
        val normalizedUrl = normalizeUrlService.normalize(url) ?: url
        
        // Check cache first
        val cached = webpageMarkdownRepository.findByUrl(normalizedUrl)
        val currentTime = System.currentTimeMillis()
        
        val (markdown, html) = if (cached != null && (currentTime - cached.updatedAtEpochMs) < CACHE_TTL_MS) {
            logger.debug("Cache hit for URL: {} (age: {} ms)", url, currentTime - cached.updatedAtEpochMs)
            cached.markdown to cached.html
        } else {
            if (cached != null) {
                logger.debug("Cache expired for URL: {} (age: {} ms)", url, currentTime - cached.updatedAtEpochMs)
            } else {
                logger.debug("Cache miss for URL: {}", url)
            }
            
            // Navigate and extract
            val context = browser.createContext()
            val page = context.newPage()
            page.navigate(url)

            // webpageExtractionService.extractWebpage modifies the webpage, so we get the html first
            val extractedHtml = page.getFullHtml()

            // Extract markdown
            val extractedMarkdown = webpageExtractionService.extractWebpage(page)
            
            // Cache the result
            webpageMarkdownRepository.upsert(
                WebpageMarkdown(
                    url = normalizedUrl,
                    markdown = extractedMarkdown,
                    html = extractedHtml,
                    createdAtEpochMs = cached?.createdAtEpochMs ?: currentTime,
                    updatedAtEpochMs = currentTime
                )
            )
            
            logger.debug("Cached markdown for URL: {}", url)
            
            extractedMarkdown to extractedHtml
        }

        // Discover links using the html
        val links = webpageLinkDiscoveryService.discoverRelevantLinksByAgent(query, html)

        logger.debug("Extracted {} chars from {}, discovered {} links", markdown.length, url, links.size)

        UrlProcessingResult(url, markdown, links)
    }
}