package io.deepsearch.application.searchstrategies.agenticbrowsersearch

import io.deepsearch.application.searchstrategies.ISearchStrategy
import io.deepsearch.application.services.IWebpageExtractionService
import io.deepsearch.application.services.IWebpageLinkDiscoveryService
import io.deepsearch.domain.browser.IBrowser
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.DispatcherProvider
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult
import io.deepsearch.domain.models.valueobjects.WebpageLink
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
    private val dispatchers: DispatcherProvider
) : IAgenticBrowserSearchStrategy {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Result of processing a single URL: extracted markdown and discovered links.
     */
    private data class UrlProcessingResult(
        val url: String,
        val markdown: String?,
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
        val browser = browserPool.acquireBrowser()
        try {
            // Step 1: Run Google link discovery and processUrl on input URL in parallel
            val step1Result = step1ProcessInitialUrl(searchQuery, browser)

            logger.info(
                "Step 1 complete: Extracted {} chars from initial URL, discovered {} Google links + {} on-page links",
                step1Result.initialResult.markdown?.length ?: 0,
                step1Result.googleLinks.size,
                step1Result.initialResult.discoveredLinks.size
            )

            // Step 2: Process all discovered links recursively, fanning out
            val allResults = step2ProcessLinksRecursively(
                initialLinks = step1Result.googleLinks + step1Result.initialResult.discoveredLinks,
                processedUrls = setOf(searchQuery.url),
                searchQuery = searchQuery,
                browser = browser
            )

            // Step 3: Collect all markdowns and return
            val allMarkdowns = listOfNotNull(step1Result.initialResult.markdown) + allResults.mapNotNull { it.markdown }
            return step3AggregateResults(searchQuery, step1Result.initialResult, allResults, allMarkdowns)
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
     */
    private suspend fun step2ProcessLinksRecursively(
        initialLinks: List<WebpageLink>,
        processedUrls: Set<String>,
        searchQuery: SearchQuery,
        browser: IBrowser
    ): List<UrlProcessingResult> = withContext(dispatchers.io) {
        val allResults = mutableListOf<UrlProcessingResult>()
        var visitedUrls = processedUrls
        var currentBatch = initialLinks
            .distinctBy { it.url }
            .filterNot { it.url in visitedUrls }

        var waveNumber = 1
        while (currentBatch.isNotEmpty()) {
            logger.debug("Step 2 Wave {}: Processing batch of {} links", waveNumber, currentBatch.size)

            // Process all URLs in this batch in parallel
            val batchResults = currentBatch.map { link ->
                async {
                    processUrl(link.url, searchQuery.query, browser)
                }
            }.awaitAll()

            // Track visited URLs
            visitedUrls = visitedUrls + batchResults.map { it.url }
            allResults.addAll(batchResults)

            // Collect new links for next wave
            val newLinks = batchResults
                .flatMap { it.discoveredLinks }
                .distinctBy { it.url }
                .filterNot { it.url in visitedUrls }

            logger.debug(
                "Wave {} complete: {} pages processed, {} markdowns extracted, {} new links discovered",
                waveNumber,
                batchResults.size,
                batchResults.count { it.markdown != null },
                newLinks.size
            )

            currentBatch = newLinks
            waveNumber++
        }

        allResults
    }

    /**
     * Step 3: Aggregate all markdowns into a single SearchResult.
     */
    private fun step3AggregateResults(
        searchQuery: SearchQuery,
        initialResult: UrlProcessingResult,
        allResults: List<UrlProcessingResult>,
        allMarkdowns: List<String>
    ): SearchResult {
        val aggregatedContent = allMarkdowns.joinToString("\n\n---\n\n")
        val allUrls = listOf(initialResult.url) + allResults.map { it.url }

        logger.info(
            "Step 3 complete: Visited {} pages, total content: {} chars",
            allUrls.size,
            aggregatedContent.length
        )

        return SearchResult(
            originalQuery = searchQuery,
            content = aggregatedContent,
            sources = allUrls
        )
    }

    /**
     * Pure function that processes a single URL: extracts markdown and discovers links in parallel.
     * Does not mutate any state - returns results as data.
     */
    private suspend fun processUrl(
        url: String,
        query: String,
        browser: IBrowser
    ): UrlProcessingResult = coroutineScope {
        logger.debug("Processing URL: {}", url)

        val context = browser.createContext()
        try {
            val page = context.newPage()
            page.navigate(url)

            // Run markdown extraction and link discovery in parallel
            val markdownDeferred = async {
                try {
                    webpageExtractionService.extractWebpage(page)
                } catch (e: Exception) {
                    logger.warn("Failed to extract markdown from {}: {}", url, e.message)
                    null
                }
            }

            val linksDeferred = async {
                try {
                    webpageLinkDiscoveryService.discoverRelevantLinksByAgent(query, page)
                } catch (e: Exception) {
                    logger.warn("Failed to discover links from {}: {}", url, e.message)
                    emptyList()
                }
            }

            val markdown = markdownDeferred.await()
            val links = linksDeferred.await()

            if (markdown != null) {
                logger.debug("Extracted {} chars from {}, discovered {} links", markdown.length, url, links.size)
            }

            UrlProcessingResult(url, markdown, links)
        } catch (e: Exception) {
            logger.warn("Failed to process URL {}: {}", url, e.message)
            UrlProcessingResult(url, null, emptyList())
        } finally {
            context.close()
        }
    }
}