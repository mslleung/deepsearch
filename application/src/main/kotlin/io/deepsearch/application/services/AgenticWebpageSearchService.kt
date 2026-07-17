package io.deepsearch.application.services

import io.deepsearch.domain.agents.*
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.agents.infra.TableMarkdownUtils
import io.deepsearch.domain.models.entities.AgenticNavIteration
import io.deepsearch.domain.models.entities.ScreenshotRecord
import io.deepsearch.domain.models.entities.ScreenshotType
import io.deepsearch.domain.repositories.IAgenticNavIterationRepository
import io.deepsearch.domain.services.AnnotatedElement
import io.deepsearch.domain.services.AnnotationTarget
import io.deepsearch.domain.services.IDomDiffService
import io.deepsearch.domain.services.IImageProcessingService
import io.deepsearch.domain.services.IHtmlToMarkdownService
import io.deepsearch.domain.services.IIterationScreenshotStorage
import io.deepsearch.domain.services.IterationScreenshotPath
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.zip
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import com.ibm.icu.lang.UScript
import com.ibm.icu.text.Transliterator
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.cjk.CJKAnalyzer
import org.apache.lucene.analysis.en.EnglishAnalyzer
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.TimeSource

private val LATIN_ASCII_TRANSLITERATOR: Transliterator =
    Transliterator.getInstance("Latin-ASCII")

private fun normalizeText(text: String): String =
    LATIN_ASCII_TRANSLITERATOR.transliterate(text)

data class CapturedImage(
    val bytes: ByteArray,
    val mimeType: String,
    val relevance: String,
    val bytesHash: ByteArray
)

data class IterationTimingBreakdown(
    val iteration: Int,
    val fetchMs: Long,
    val annotateMs: Long,
    val keywordScanMs: Long,
    val navAgentMs: Long,
    val extractionPipelineMs: Long,
    val actionsMs: Long,
    val totalMs: Long
)

data class SearchTimingBreakdown(
    val initialLoadMs: Long,
    val cookieDismissMs: Long,
    val iterations: List<IterationTimingBreakdown>,
    val totalMs: Long
)

enum class TerminationReason {
    ANSWER_FOUND,
    PAGE_IRRELEVANT,
    NO_UNEXPLORED_POTENTIAL,
    REFEREE_OVERRIDE,
    MAX_ITERATIONS
}

data class AgenticPageSearchResult(
    val answer: String?,
    val evidence: String?,
    val contentDate: String?,
    val actionsPerformed: List<ActionWithOutcome>,
    val observations: List<String>,
    val success: Boolean,
    val totalTokenUsage: TokenUsageMetrics,
    val discoveredUrls: List<String> = emptyList(),
    val capturedImages: List<CapturedImage> = emptyList(),
    val timingBreakdown: SearchTimingBreakdown? = null,
    val terminationReason: TerminationReason = TerminationReason.MAX_ITERATIONS
)

interface IAgenticWebpageSearchService {
    suspend fun searchWithinPage(
        url: String,
        query: String,
        sessionId: SessionId
    ): AgenticPageSearchResult

    suspend fun searchWithinPage(
        page: IBrowserPage,
        url: String,
        query: String,
        sessionId: SessionId,
        onLinkDiscovered: (suspend (String) -> Unit)? = null
    ): AgenticPageSearchResult
}

class AgenticWebpageSearchService(
    private val browserPool: IBrowserPool,
    private val fullPageNavigationAgent: IFullPageNavigationAgent,
    private val visualContentExtractionAgent: IVisualContentExtractionAgent,
    private val contentRegionLocatorAgent: IContentRegionLocatorAgent,
    private val imageProcessingService: IImageProcessingService,
    private val domDiffService: IDomDiffService,
    private val tokenUsageService: ILlmTokenUsageService,
    private val dispatcherProvider: IDispatcherProvider,
    private val agenticTableConversionAgent: IAgenticTableConversionAgent,
    private val iterationScreenshotStorage: IIterationScreenshotStorage,
    private val agenticNavIterationRepository: IAgenticNavIterationRepository,
    private val htmlToMarkdownService: IHtmlToMarkdownService,
) : IAgenticWebpageSearchService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    private val actionSerializer = Json { ignoreUnknownKeys = true }

    private fun fireAndForget(block: suspend () -> Unit) {
        CoroutineScope(dispatcherProvider.io + NonCancellable).launch {
            try {
                block()
            } catch (e: Exception) {
                logger.warn("Background task failed: {}", e.message)
            }
        }
    }

    companion object {
        private const val MAX_ITERATIONS = 12
        private const val REFEREE_OVERRIDE_AFTER = 2
        private const val MAX_FAILED_CLICKS = 2
        private const val POST_CLICK_DELAY_MS = 150L
        private const val POST_CLICK_SETTLE_DELAY_MS = 300L
        private const val POST_SCROLL_DELAY_MS = 50L
        private const val POST_TYPE_DELAY_MS = 500L
        private const val COOKIE_DISMISS_DELAY_MS = 150L
        private const val MIN_PAGE_HEIGHT_FOR_CROP = 4000
        private const val PAGE_HEIGHT_VIEWPORT_THRESHOLD = 10000

        /**
         * CSS selectors for common Consent Management Platform "Accept All" buttons.
         * Ordered by market share so the most common CMPs are checked first.
         */
        private val COOKIE_ACCEPT_SELECTORS = listOf(
            // OneTrust (used by ~30% of top sites)
            "#onetrust-accept-btn-handler",
            // Cookiebot
            "#CybotCookiebotDialogBodyLevelButtonLevelOptinAllowAll",
            "#CybotCookiebotDialogBodyButtonAccept",
            // Osano Cookie Consent
            ".cc-btn.cc-allow",
            ".cc-btn.cc-dismiss",
            // Didomi
            "#didomi-notice-agree-button",
            // iubenda
            ".iubenda-cs-accept-btn",
            // Complianz
            "button.cmplz-accept",
            // Usercentrics
            "[data-testid='uc-accept-all-button']",
            // TrustArc
            "#truste-consent-button",
            // Quantcast
            ".qc-cmp2-summary-buttons button[mode='primary']",
            // GDPR Cookie Compliance (WordPress plugin)
            ".moove-gdpr-infobar-allow-all",
            // HubSpot
            "#hs-eu-confirmation-button",
        )

        /**
         * CSS selectors for cookie consent overlay containers to remove as a fallback.
         * These cover both the dialog and any backdrop/underlay that blocks interaction.
         */
        private val COOKIE_OVERLAY_SELECTORS = listOf(
            "#onetrust-consent-sdk",
            "#CybotCookiebotDialog",
            "#CybotCookiebotDialogBodyUnderlay",
            ".cc-window",
            ".cc-banner",
            "#truste-consent-track",
            ".truste_overlay",
            "#didomi-host",
            "#didomi-popup",
            "#usercentrics-root",
            ".cmplz-cookiebanner",
            "#iubenda-cs-banner",
            ".qc-cmp2-container",
            ".moove-gdpr-cookie-info-bar",
            "#hs-eu-cookie-confirmation",
        )
    }

    // --- Inner types ---

    private enum class ActionEffect {
        PAGE_CHANGED,
        PAGE_UNCHANGED
    }

    private class NavigationLoopState(
        val url: String,
        val query: String,
        val sessionId: SessionId,
        val onLinkDiscovered: (suspend (String) -> Unit)?,
        val actionsPerformed: MutableList<ActionWithOutcome> = mutableListOf(),
        val discoveredUrls: MutableList<String> = mutableListOf(),
        val capturedImages: MutableList<CapturedImage> = mutableListOf(),
        val capturedHashes: MutableSet<String> = mutableSetOf(),
        var aggregatedTokenUsage: TokenUsageMetrics = TokenUsageMetrics.empty("gemini-3.1-flash-lite"),
        var previousClickScreenshot: ByteArray? = null,
        var previousDomSnapshot: IBrowserPage.DomSnapshot? = null,
        var lastClickVisualChanged: Boolean = false,
        val failedClickDescs: MutableMap<String, Int> = mutableMapOf(),
        val offPageClickedDescs: MutableSet<String> = mutableSetOf(),
        var pageState: List<String> = emptyList(),
        var navigationMode: NavigationMode = NavigationMode.FULL_PAGE,
        val extractedRegionContent: MutableList<ExtractedContent> = mutableListOf(),
        val extractedTextHashes: MutableSet<String> = mutableSetOf(),
        var contentObservation: String? = null,
        var explorationDirections: List<ExplorationDirection> = emptyList(),
        var currentDirectionHint: String? = null,
        var directionStartIndex: Int = 0,
        val directionBoundaries: MutableList<Pair<Int, String?>> = mutableListOf(),
        var queryKeywords: List<String> = emptyList(),
        var keywordsRefined: Boolean = false,
        var keywordScan: List<KeywordScanEntry> = emptyList(),
        var lastScannedKeywords: List<String> = emptyList(),
        val iterationTimings: MutableList<IterationTimingBreakdown> = mutableListOf(),
        val progressLog: MutableList<IterationProgress> = mutableListOf(),
        val keywordCountBaseline: MutableMap<String, Pair<Int, Int>> = mutableMapOf(),
        var keywordRevealsThisIteration: Int = 0,
        var consecutiveBarrenContinues: Int = 0
    )

    // --- Public API ---

    override suspend fun searchWithinPage(
        url: String,
        query: String,
        sessionId: SessionId
    ): AgenticPageSearchResult {
        logger.info("Starting agentic page search for query='{}' on url={}", query, url)

        return browserPool.withPage { page ->
            page.navigate(url)
            page.waitForLoad()
            doFullPageSearchWithinPage(page, url, query, sessionId)
        }
    }

    override suspend fun searchWithinPage(
        page: IBrowserPage,
        url: String,
        query: String,
        sessionId: SessionId,
        onLinkDiscovered: (suspend (String) -> Unit)?
    ): AgenticPageSearchResult =
        doFullPageSearchWithinPage(page, url, query, sessionId, onLinkDiscovered)

    // --- Core navigation loop ---

    private suspend fun doFullPageSearchWithinPage(
        page: IBrowserPage,
        url: String,
        query: String,
        sessionId: SessionId,
        onLinkDiscovered: (suspend (String) -> Unit)? = null
    ): AgenticPageSearchResult {
        logger.info("Starting FULL-PAGE agentic search for query='{}' on url={}", query, url)

        val clock = TimeSource.Monotonic
        val searchStart = clock.markNow()

        val cookieStart = clock.markNow()
        dismissCookieBanner(page, url)
        val cookieDismissMs = (clock.markNow() - cookieStart).inWholeMilliseconds

        val loadStart = clock.markNow()
        val actualViewportHeight = page.captureDomSnapshot().viewportHeight
        val initialLoadMs = (clock.markNow() - loadStart).inWholeMilliseconds

        val state = NavigationLoopState(
            url = url,
            query = query,
            sessionId = sessionId,
            onLinkDiscovered = onLinkDiscovered,
            queryKeywords = deriveInitialKeywords(query)
        )
        var cachedScreenshot: IBrowserPage.Screenshot? = null
        var iteration = 0

        while (iteration < MAX_ITERATIONS) {
            iteration++
            val iterStart = clock.markNow()
            currentCoroutineContext().ensureActive()
            logger.debug("Full-page search iteration {}/{} for {}", iteration, MAX_ITERATIONS, url)

            val setup = prepareIteration(page, state, cachedScreenshot, iteration)
            cachedScreenshot = null

            val kwScanStart = clock.markNow()
            refreshKeywordScan(page, state, pageChangedSinceLastScan = !setup.screenshotWasCached)
            val keywordScanMs = (clock.markNow() - kwScanStart).inWholeMilliseconds

            val directionActions = state.actionsPerformed.subList(
                state.directionStartIndex, state.actionsPerformed.size
            ).toList()
            val exploredActions = buildExploredActions(state)
            val navInput = FullPageNavigationInput(
                fullPageScreenshot = setup.labeledScreenshot,
                query = query,
                previousActions = directionActions,
                pageUrl = url,
                pageTitle = setup.title,
                pageState = state.pageState,
                navigationMode = setup.navigationMode,
                explorationDirections = state.explorationDirections,
                extractedContent = state.extractedRegionContent.toList(),
                keywordScan = state.keywordScan,
                currentIteration = iteration,
                maxIterations = MAX_ITERATIONS,
                progressLog = state.progressLog,
                exploredActions = exploredActions
            )

            val extractionFlow: Flow<Pair<ExtractionPipelineResult?, Long>> = flow {
                val extStart = clock.markNow()
                if (!setup.screenshotWasCached) {
                    try {
                        val result = runExtractionPipeline(
                            setup.screenshot, query, state.extractedRegionContent.toList(),
                            setup.imgWidth, setup.imgHeight, page, setup.navigationMode,
                            actualViewportHeight, sessionId, iteration, url
                        )
                        emit(result to (clock.markNow() - extStart).inWholeMilliseconds)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn("Extraction pipeline failed at iter={}: {}", iteration, e.message)
                        emit(ExtractionPipelineResult(
                            regionLocatorTokenUsage = TokenUsageMetrics.empty("gemini-3.1-flash-lite"),
                            regionLocatorMs = 0,
                            extractionResult = ExtractionResult(emptyList(), emptyList()),
                            extractionMs = 0,
                            extractionObservation = null,
                            extractedTextHashes = emptySet(),
                            capturedHashes = emptySet()
                        ) to (clock.markNow() - extStart).inWholeMilliseconds)
                    }
                } else {
                    emit(null to 0L)
                }
            }

            val navigationFlow: Flow<Pair<FullPageNavigationOutput, Long>> = flow {
                val navStart = clock.markNow()
                val navOutput = fullPageNavigationAgent.generate(navInput)
                val navAgentMs = (clock.markNow() - navStart).inWholeMilliseconds
                emit(navOutput to navAgentMs)
            }

            val (extractionPair, navPair) = extractionFlow.zip(navigationFlow) { extraction, nav ->
                extraction to nav
            }.first()
            val (extractionResult, extractionPipelineMs) = extractionPair
            val (navOutput, navAgentMs) = navPair

            val extractionGain = if (extractionResult != null) {
                storeExtractionResults(extractionResult, state, sessionId)
            } else 0

            val resolvedActions = resolveClicksByElementIndex(
                navOutput.actions, setup.elementIndex
            )

            state.aggregatedTokenUsage = state.aggregatedTokenUsage + navOutput.tokenUsage
            tokenUsageService.recordTokenUsage(
                sessionId = sessionId,
                agentName = "FullPageNavigationAgent",
                modelName = navOutput.tokenUsage.modelName,
                promptTokens = navOutput.tokenUsage.promptTokens,
                outputTokens = navOutput.tokenUsage.outputTokens,
                totalTokens = navOutput.tokenUsage.totalTokens
            )

            state.pageState = navOutput.pageState
            state.explorationDirections = navOutput.explorationDirections
            if (navOutput.queryKeywords.isNotEmpty()) {
                state.queryKeywords = navOutput.queryKeywords
                state.keywordsRefined = true
            }

            if (normalizeDirectionName(navOutput.currentDirection) != normalizeDirectionName(state.currentDirectionHint)) {
                logger.info(
                    "Direction switch at iter={}: '{}' -> '{}'",
                    iteration, state.currentDirectionHint?.take(60), navOutput.currentDirection?.take(60)
                )
                state.currentDirectionHint = navOutput.currentDirection
                state.directionStartIndex = state.actionsPerformed.size
                state.directionBoundaries.add(state.actionsPerformed.size to navOutput.currentDirection)
            }

            logger.info(
                "iter={}: {} directions ({} unexplored), searchComplete={}, allExhausted={}, dir={}, actions={}",
                iteration, navOutput.explorationDirections.size,
                navOutput.explorationDirections.count { it.status == "unexplored" },
                navOutput.searchComplete,
                navOutput.allDirectionsExhausted,
                navOutput.currentDirection?.take(60),
                resolvedActions.size
            )

            if (navOutput.searchComplete) {
                val iterMs = (clock.markNow() - iterStart).inWholeMilliseconds
                state.iterationTimings.add(IterationTimingBreakdown(
                    iteration = iteration, fetchMs = setup.fetchMs, annotateMs = setup.annotateMs,
                    keywordScanMs = keywordScanMs, navAgentMs = navAgentMs,
                    extractionPipelineMs = extractionPipelineMs, actionsMs = 0, totalMs = iterMs
                ))
                val terminationReason = determineTerminationReason(navOutput.continuationAssessment)
                logger.info(
                    "Search stopping at iter={} ({}ms) — reason={}",
                    iteration, iterMs, terminationReason
                )
                saveIterationMetadata(
                    sessionId, url, iteration, navOutput, setup.screenshot.mimeType, iterMs
                )
                val totalMs = (clock.markNow() - searchStart).inWholeMilliseconds
                val timing = SearchTimingBreakdown(initialLoadMs, cookieDismissMs, state.iterationTimings.toList(), totalMs)
                return handleSearchComplete(state, iteration, timing, terminationReason)
            }

            if (resolvedActions.isEmpty()) {
                val iterMs = (clock.markNow() - iterStart).inWholeMilliseconds
                state.iterationTimings.add(IterationTimingBreakdown(
                    iteration = iteration, fetchMs = setup.fetchMs, annotateMs = setup.annotateMs,
                    keywordScanMs = keywordScanMs, navAgentMs = navAgentMs,
                    extractionPipelineMs = extractionPipelineMs, actionsMs = 0, totalMs = iterMs
                ))
                logger.info(
                    "TIMING iter={} total={}ms | fetch={}ms annotate={}ms kwScan={}ms nav={}ms extraction={}ms (no actions)",
                    iteration, iterMs, setup.fetchMs, setup.annotateMs, keywordScanMs, navAgentMs, extractionPipelineMs
                )
                saveIterationMetadata(
                    sessionId, url, iteration, navOutput, setup.screenshot.mimeType, iterMs
                )
                state.progressLog.add(IterationProgress(iteration, extractionGain, state.keywordRevealsThisIteration))
                val refereeResult = checkRefereeOverride(state, extractionGain)
                if (refereeResult != null) {
                    val totalMs = (clock.markNow() - searchStart).inWholeMilliseconds
                    val timing = SearchTimingBreakdown(initialLoadMs, cookieDismissMs, state.iterationTimings.toList(), totalMs)
                    return buildFinalResult(state, timing, refereeResult)
                }
                continue
            }

            val postNavStart = clock.markNow()

            var pageChanged = false
            for ((actionIdx, action) in resolvedActions.withIndex()) {
                state.actionsPerformed.add(
                    if (actionIdx == 0) ActionWithOutcome(action, observation = navOutput.observation)
                    else ActionWithOutcome(action)
                )

                val effect = when (action) {
                    is NavigationAction.Click ->
                        if (setup.navigationMode == NavigationMode.VIEWPORT)
                            handleViewportClick(action, page, state, setup.screenshot.bytes)
                        else
                            handleFullPageClick(
                                action, page, state, setup.screenshot.bytes,
                                setup.imgWidth, setup.imgHeight, actualViewportHeight
                            )

                    is NavigationAction.Type ->
                        if (setup.navigationMode == NavigationMode.VIEWPORT)
                            handleViewportType(action, page, state, setup.imgWidth, setup.imgHeight)
                        else
                            handleFullPageType(
                                action, page, state, setup.screenshot.bytes,
                                setup.imgWidth, setup.imgHeight, actualViewportHeight
                            )

                    is NavigationAction.ScrollAt ->
                        handleScrollAt(action, page, state, setup.imgWidth, setup.imgHeight)

                    else -> {
                        logger.warn("Unsupported action in full-page mode: {}", action)
                        ActionEffect.PAGE_UNCHANGED
                    }
                }
                if (effect == ActionEffect.PAGE_CHANGED) {
                    pageChanged = true
                    if (action !is NavigationAction.Click) {
                        val wasViewport = state.navigationMode == NavigationMode.VIEWPORT
                        val hasOverlay = page.hasModalOverlay()
                        state.navigationMode = if (hasOverlay) NavigationMode.VIEWPORT else NavigationMode.FULL_PAGE
                        if (state.navigationMode == NavigationMode.VIEWPORT && !wasViewport) {
                            logger.info("Modal overlay detected after action — switching to viewport mode")
                        } else if (state.navigationMode == NavigationMode.FULL_PAGE && wasViewport) {
                            logger.info("Overlay dismissed after action — returning to full-page mode")
                        }
                    }
                    break
                }
            }
            val actionsMs = (clock.markNow() - postNavStart).inWholeMilliseconds
            val iterMs = (clock.markNow() - iterStart).inWholeMilliseconds
            state.iterationTimings.add(IterationTimingBreakdown(
                iteration = iteration, fetchMs = setup.fetchMs, annotateMs = setup.annotateMs,
                keywordScanMs = keywordScanMs, navAgentMs = navAgentMs,
                extractionPipelineMs = extractionPipelineMs, actionsMs = actionsMs, totalMs = iterMs
            ))
            logger.info(
                "TIMING iter={} total={}ms | fetch={}ms annotate={}ms kwScan={}ms nav={}ms extraction={}ms actions={}ms",
                iteration, iterMs, setup.fetchMs, setup.annotateMs, keywordScanMs, navAgentMs, extractionPipelineMs, actionsMs
            )
            saveIterationMetadata(
                sessionId, url, iteration, navOutput, setup.screenshot.mimeType, iterMs
            )

            state.progressLog.add(IterationProgress(iteration, extractionGain, state.keywordRevealsThisIteration))
            val refereeResult = checkRefereeOverride(state, extractionGain)
            if (refereeResult != null) {
                val totalMs = (clock.markNow() - searchStart).inWholeMilliseconds
                val timing = SearchTimingBreakdown(initialLoadMs, cookieDismissMs, state.iterationTimings.toList(), totalMs)
                return buildFinalResult(state, timing, refereeResult)
            }

            if (!pageChanged) {
                cachedScreenshot = setup.screenshot
            }
        }

        val totalMs = (clock.markNow() - searchStart).inWholeMilliseconds
        val timing = SearchTimingBreakdown(initialLoadMs, cookieDismissMs, state.iterationTimings.toList(), totalMs)
        return buildFinalResult(state, timing, TerminationReason.MAX_ITERATIONS)
    }

    /**
     * The LLM sometimes prefixes direction names with the "D1." list markers from the prompt,
     * which would register as a spurious direction switch and reset the turn history.
     */
    private fun normalizeDirectionName(name: String?): String? =
        name?.replace(Regex("^D\\d+\\.\\s*"), "")?.trim()?.lowercase()?.ifEmpty { null }

    private fun determineTerminationReason(assessment: ContinuationAssessment?): TerminationReason {
        if (assessment == null) return TerminationReason.ANSWER_FOUND
        return when {
            assessment.answerFound.satisfied -> TerminationReason.ANSWER_FOUND
            !assessment.pageRelevant.satisfied -> TerminationReason.PAGE_IRRELEVANT
            !assessment.unexploredPotential.satisfied -> TerminationReason.NO_UNEXPLORED_POTENTIAL
            else -> TerminationReason.ANSWER_FOUND
        }
    }

    private fun checkRefereeOverride(state: NavigationLoopState, extractionGain: Int): TerminationReason? {
        val totalGain = extractionGain + state.keywordRevealsThisIteration
        if (totalGain == 0) {
            state.consecutiveBarrenContinues++
        } else {
            state.consecutiveBarrenContinues = 0
        }
        return if (state.consecutiveBarrenContinues >= REFEREE_OVERRIDE_AFTER) {
            logger.info(
                "Referee override: {} consecutive iterations with no observed progress",
                state.consecutiveBarrenContinues
            )
            TerminationReason.REFEREE_OVERRIDE
        } else null
    }

    private fun buildExploredActions(state: NavigationLoopState): List<ExploredAction> {
        val priorActions = state.actionsPerformed.subList(0, state.directionStartIndex)
        if (priorActions.isEmpty()) return emptyList()

        return priorActions.mapIndexedNotNull { idx, entry ->
            val click = entry.action as? NavigationAction.Click ?: return@mapIndexedNotNull null
            val target = click.target ?: return@mapIndexedNotNull null
            val direction = findDirectionForActionIndex(idx, state.directionBoundaries)
            ExploredAction(target = target, direction = direction, outcome = entry.outcome)
        }
    }

    private fun findDirectionForActionIndex(
        actionIndex: Int,
        boundaries: List<Pair<Int, String?>>
    ): String? {
        var direction: String? = null
        for ((startIdx, dirName) in boundaries) {
            if (startIdx > actionIndex) break
            direction = dirName
        }
        return direction
    }

    // --- Keyword scan (page-wide Ctrl+F grounding for the navigation agent) ---

    /**
     * Derive first-iteration scan keywords from the raw query using Lucene tokenization
     * with standard stopword removal. For CJK queries, uses [CJKAnalyzer] with bigram
     * tokenization; for Latin/other scripts, uses [StandardAnalyzer] with English
     * stopwords (no stemming, since the keyword scan does exact text matching).
     * The navigation agent supplies refined keywords from iteration 2 onwards.
     */
    private fun deriveInitialKeywords(query: String): List<String> {
        val (analyzer, minTokenLength) = chooseQueryAnalyzer(query)
        val tokens = mutableListOf<String>()
        analyzer.use { a ->
            a.tokenStream("query", query).use { stream ->
                val termAttr = stream.addAttribute(CharTermAttribute::class.java)
                stream.reset()
                while (stream.incrementToken()) {
                    val term = termAttr.toString()
                    if (term.length >= minTokenLength) tokens.add(term)
                }
                stream.end()
            }
        }
        return tokens.distinct().take(8)
    }

    private fun chooseQueryAnalyzer(query: String): Pair<Analyzer, Int> {
        val scriptCounts = mutableMapOf<Int, Int>()
        query.codePoints().forEach { cp ->
            val script = UScript.getScript(cp)
            if (script != UScript.COMMON && script != UScript.INHERITED) {
                scriptCounts.merge(script, 1, Int::plus)
            }
        }
        val dominantScript = scriptCounts.maxByOrNull { it.value }?.key
        return when (dominantScript) {
            UScript.HAN, UScript.HIRAGANA, UScript.KATAKANA, UScript.HANGUL ->
                CJKAnalyzer() to 1
            else ->
                StandardAnalyzer(EnglishAnalyzer.getDefaultStopSet()) to 4
        }
    }

    /**
     * Re-run the page-wide text match scan when the page changed or the keyword set changed.
     * One CDP round trip (~10ms); counts include content hidden behind accordions/tabs.
     */
    private suspend fun refreshKeywordScan(
        page: IBrowserPage,
        state: NavigationLoopState,
        pageChangedSinceLastScan: Boolean
    ) {
        state.keywordRevealsThisIteration = 0
        if (state.queryKeywords.isEmpty()) return
        if (!pageChangedSinceLastScan && state.queryKeywords == state.lastScannedKeywords) return
        try {
            val counts = page.countTextMatches(state.queryKeywords)
            state.keywordScan = state.queryKeywords.mapNotNull { kw ->
                counts[kw]?.let { c ->
                    val hiddenCount = c.total - c.visible
                    if (hiddenCount > 0) {
                        KeywordScanEntry(
                            keyword = kw,
                            visibleCount = c.visible,
                            totalCount = c.total,
                            firstContext = c.firstContext
                        )
                    } else null
                }
            }
            state.lastScannedKeywords = state.queryKeywords

            for (kw in state.queryKeywords) {
                val c = counts[kw] ?: continue
                val baseline = state.keywordCountBaseline[kw]
                if (baseline == null) {
                    state.keywordCountBaseline[kw] = c.visible to c.total
                } else {
                    val (prevVisible, _) = baseline
                    if (c.visible > prevVisible) {
                        state.keywordRevealsThisIteration += (c.visible - prevVisible)
                    }
                    state.keywordCountBaseline[kw] = maxOf(c.visible, prevVisible) to c.total
                }
            }

            logger.debug(
                "Keyword scan: {} (reveals this iter: {})",
                state.keywordScan.joinToString(", ") { "'${it.keyword}':${it.totalCount}(${it.visibleCount}v)" },
                state.keywordRevealsThisIteration
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Keyword scan failed: {}", e.message)
        }
    }

    // --- Iteration setup and extraction helpers ---

    private suspend fun prepareIteration(
        page: IBrowserPage,
        state: NavigationLoopState,
        cachedScreenshot: IBrowserPage.Screenshot?,
        iteration: Int
    ): IterationSetup {
        val clock = TimeSource.Monotonic

        val navigationMode = state.navigationMode
        if (navigationMode == NavigationMode.VIEWPORT) {
            logger.debug("Viewport mode active — using viewport screenshot for iteration {}", iteration)
        }
        if (navigationMode == NavigationMode.FULL_PAGE) {
            page.scrollToPercentage(0)
        }

        val fetchStart = clock.markNow()
        val screenshotWasCached = cachedScreenshot != null
        val fetchResult = page.fetchAgenticIterationData(navigationMode, cachedScreenshot)
        val fetchMs = (clock.markNow() - fetchStart).inWholeMilliseconds
        val screenshot = fetchResult.screenshot
        val title = fetchResult.title
        val interactiveElements = fetchResult.interactiveElements
        val scrollableContainers = fetchResult.scrollableContainers

        if (scrollableContainers.isNotEmpty()) {
            logger.debug("Found {} scrollable containers: {}", scrollableContainers.size, scrollableContainers)
        }

        fireAndForget {
            iterationScreenshotStorage.saveRawScreenshot(
                state.sessionId, state.url, iteration, screenshot.bytes, screenshot.mimeType
            )
        }

        val annotateStart = clock.markNow()
        val (imgWidth, imgHeight) = withContext(dispatcherProvider.default) {
            imageProcessingService.getImageDimensions(screenshot.bytes)
        }

        val currentDomSnapshot = fetchResult.domSnapshot
        if (state.previousClickScreenshot != null) {
            applyVisualDiffAfterClick(screenshot.bytes, state, currentDomSnapshot)
        }
        state.previousDomSnapshot = currentDomSnapshot

        val (labeledScreenshot, elementIndex) = if (interactiveElements.isNotEmpty()) {
            annotateScreenshot(screenshot, interactiveElements)
        } else {
            Pair(screenshot, emptyMap())
        }

        val annotateMs = (clock.markNow() - annotateStart).inWholeMilliseconds

        fireAndForget {
            iterationScreenshotStorage.saveAnnotatedScreenshot(
                state.sessionId, state.url, iteration, labeledScreenshot.bytes
            )
        }

        val scrollStateHint = if (scrollableContainers.isNotEmpty()) {
            buildString {
                scrollableContainers.forEach { c ->
                    append("- ${c.description}:")
                    if (c.hasMoreBelow || c.hasMoreAbove) {
                        append(" ${c.verticalScrollPercent}% scrolled vertically")
                        if (c.hasMoreBelow) append(", MORE CONTENT BELOW")
                        if (c.hasMoreAbove) append(", more content above")
                        append(".")
                    }
                    if (c.hasMoreRight || c.hasMoreLeft) {
                        append(" ${c.horizontalScrollPercent}% scrolled horizontally")
                        if (c.hasMoreRight) append(", MORE CONTENT TO THE RIGHT")
                        if (c.hasMoreLeft) append(", more content to the left")
                        append(".")
                    }
                    appendLine()
                }
            }.trimEnd()
        } else null

        val interactiveElementsHint = if (interactiveElements.isNotEmpty()) {
            interactiveElements
                .filter { it.text.isNotBlank() }
                .joinToString("\n") { e ->
                    val type = e.role ?: e.tag
                    "- [$type] \"${e.text.take(80)}\""
                }
        } else null

        return IterationSetup(
            screenshot = screenshot,
            labeledScreenshot = labeledScreenshot,
            elementIndex = elementIndex,
            title = title,
            interactiveElements = interactiveElements,
            imgWidth = imgWidth,
            imgHeight = imgHeight,
            fetchMs = fetchMs,
            annotateMs = annotateMs,
            screenshotWasCached = screenshotWasCached,
            scrollStateHint = scrollStateHint,
            interactiveElementsHint = interactiveElementsHint,
            navigationMode = navigationMode
        )
    }

    private suspend fun runExtractionPipeline(
        screenshot: IBrowserPage.Screenshot,
        query: String,
        extractedRegionContentSnapshot: List<ExtractedContent>,
        imgWidth: Int,
        imgHeight: Int,
        page: IBrowserPage,
        navigationMode: NavigationMode,
        actualViewportHeight: Int,
        sessionId: SessionId,
        iteration: Int,
        url: String
    ): ExtractionPipelineResult {
        val clock = TimeSource.Monotonic
        val locatorInput = ContentRegionLocatorInput(
            screenshot = IBrowserPage.Screenshot(bytes = screenshot.bytes, mimeType = ImageMimeType.PNG),
            query = query,
            extractedContent = extractedRegionContentSnapshot
        )

        val locatorStart = clock.markNow()
        val locatorOutput = contentRegionLocatorAgent.generate(locatorInput)
        val regionLocatorMs = (clock.markNow() - locatorStart).inWholeMilliseconds

        var extractionResult = ExtractionResult(emptyList(), emptyList())
        var extractionMs = 0L
        val pipelineTextHashes = mutableSetOf<String>()
        val pipelineCapturedHashes = mutableSetOf<String>()

        if (locatorOutput.regions.isNotEmpty()) {
            val extractionStart = clock.markNow()
            extractionResult = extractVisualSegmentationRegions(
                screenshot.bytes, imgWidth, imgHeight,
                locatorOutput.regions, query, pipelineTextHashes,
                pipelineCapturedHashes, page, navigationMode, actualViewportHeight,
                sessionId, iteration, url
            )
            extractionMs = (clock.markNow() - extractionStart).inWholeMilliseconds
        }

        return ExtractionPipelineResult(
            regionLocatorTokenUsage = locatorOutput.tokenUsage,
            regionLocatorMs = regionLocatorMs,
            extractionResult = extractionResult,
            extractionMs = extractionMs,
            extractionObservation = locatorOutput.observation,
            extractedTextHashes = pipelineTextHashes,
            capturedHashes = pipelineCapturedHashes
        )
    }

    private suspend fun storeExtractionResults(
        result: ExtractionPipelineResult,
        state: NavigationLoopState,
        sessionId: SessionId
    ): Int {
        val newTextCount = result.extractedTextHashes.count { it !in state.extractedTextHashes }
        val newImageCount = result.capturedHashes.count { it !in state.capturedHashes }

        val newContent = result.extractionResult.extractedContent
        if (newContent.isNotEmpty()) {
            state.extractedRegionContent.addAll(newContent)
        }
        if (result.extractionResult.capturedImages.isNotEmpty()) {
            state.capturedImages.addAll(result.extractionResult.capturedImages)
        }
        state.extractedTextHashes.addAll(result.extractedTextHashes)
        state.capturedHashes.addAll(result.capturedHashes)
        if (result.extractionObservation != null) {
            state.contentObservation = result.extractionObservation
        }

        state.aggregatedTokenUsage = state.aggregatedTokenUsage + result.regionLocatorTokenUsage

        tokenUsageService.recordTokenUsage(
            sessionId = sessionId,
            agentName = "ContentRegionLocatorAgent",
            modelName = result.regionLocatorTokenUsage.modelName,
            promptTokens = result.regionLocatorTokenUsage.promptTokens,
            outputTokens = result.regionLocatorTokenUsage.outputTokens,
            totalTokens = result.regionLocatorTokenUsage.totalTokens
        )

        return newTextCount + newImageCount
    }

    private suspend fun handleFullPageClick(
        action: NavigationAction.Click,
        page: IBrowserPage,
        state: NavigationLoopState,
        rawScreenshotBytes: ByteArray,
        imgWidth: Int,
        imgHeight: Int,
        viewportHeight: Int = 1080
    ): ActionEffect {
        val desc = action.target ?: "[${action.elementLabel}]"

        if (desc in state.offPageClickedDescs) {
            logger.debug("Skipping re-click on known off-page element '{}'", desc)
            updateLastActionOutcome(
                state,
                "Element '$desc' already navigates OFF this page. " +
                        "Do NOT click it again. Find the answer elsewhere on the CURRENT page, " +
                        "or use answer_found / give_up."
            )
            return ActionEffect.PAGE_UNCHANGED
        }

        val centerX = action.resolvedCenterX
        val centerY = action.resolvedCenterY

        if (centerX == null || centerY == null) {
            logger.warn("Could not resolve click target for '{}' (label={}), attempting text-based fallback", desc, action.elementLabel)
            val labelText = action.target
            if (labelText != null) {
                val fallbackResult = attemptTextBasedClick(page, labelText, desc, state)
                if (fallbackResult != null) return fallbackResult
            }
            updateLastActionOutcome(state, "Could not locate element matching '$desc'.")
            return ActionEffect.PAGE_UNCHANGED
        }

        val coords = scrollAndMapToViewport(page, centerX, centerY, imgHeight, viewportHeight)
        val (viewportX, viewportY) = coords

        logger.debug(
            "Full-page click '{}' -> viewport ({},{}) (reason: {}), resolvedCenter=({},{})",
            desc, viewportX, viewportY, action.reason, centerX, centerY
        )

        if (viewportX < 0 || viewportY < 0 || viewportX > imgWidth || viewportY > viewportHeight) {
            logger.warn(
                "Invalid viewport coordinates ({},{}) for '{}' (viewport={}x{}), attempting text-based fallback",
                viewportX, viewportY, desc, imgWidth, viewportHeight
            )
            val labelText = action.target
            if (labelText != null) {
                val fallbackResult = attemptTextBasedClick(page, labelText, desc, state)
                if (fallbackResult != null) return fallbackResult
            }
            updateLastActionOutcome(
                state,
                "Click on '$desc' failed: coordinates ($viewportX,$viewportY) are outside viewport and text fallback failed."
            )
            return ActionEffect.PAGE_UNCHANGED
        }

        state.previousClickScreenshot = rawScreenshotBytes

        val result = page.guardedClickAndCheckOverlay(viewportX, viewportY)

        if (result.navigatedAwayTo != null) {
            logger.info(
                "Click on '{}' intercepted navigation to {} — page unchanged",
                desc, result.navigatedAwayTo
            )
            val targetUrl = result.navigatedAwayTo!!
            state.discoveredUrls.add(targetUrl)
            state.onLinkDiscovered?.invoke(targetUrl)
            state.offPageClickedDescs.add(desc)
            state.previousClickScreenshot = null
            updateLastActionOutcome(state, buildOffPageOutcome(targetUrl))
            return ActionEffect.PAGE_UNCHANGED
        }

        val wasViewport = state.navigationMode == NavigationMode.VIEWPORT
        state.navigationMode = if (result.hasModalOverlay) NavigationMode.VIEWPORT else NavigationMode.FULL_PAGE
        if (state.navigationMode == NavigationMode.VIEWPORT && !wasViewport) {
            logger.info("Modal overlay detected after click — switching to viewport mode")
        } else if (state.navigationMode == NavigationMode.FULL_PAGE && wasViewport) {
            logger.info("Overlay dismissed after click — returning to full-page mode")
        }
        return ActionEffect.PAGE_CHANGED
    }

    private suspend fun attemptTextBasedClick(
        page: IBrowserPage,
        labelText: String,
        desc: String,
        state: NavigationLoopState
    ): ActionEffect? {
        val sanitizedText = labelText.replace("'", "\\'").trim()
        if (sanitizedText.isBlank()) return null

        val xpath = "//*[contains(normalize-space(text()), '$sanitizedText')]"
        return try {
            logger.info("Attempting text-based click fallback with XPath for '{}'", desc)
            page.clickByXPathSelector(xpath)
            logger.info("Text-based XPath click succeeded for '{}'", desc)
            state.previousClickScreenshot = null
            ActionEffect.PAGE_CHANGED
        } catch (e: Exception) {
            logger.warn("Text-based XPath click failed for '{}': {}", desc, e.message)
            null
        }
    }

    private suspend fun handleFullPageType(
        action: NavigationAction.Type,
        page: IBrowserPage,
        state: NavigationLoopState,
        rawScreenshotBytes: ByteArray,
        imgWidth: Int,
        imgHeight: Int,
        viewportHeight: Int = 1080
    ): ActionEffect {
        val cx = action.x
        val cy = action.y
        val pageX = ((cx / 1000.0) * imgWidth).toInt()
        val pageY = ((cy / 1000.0) * imgHeight).toInt()
        val targetScrollY = (pageY - viewportHeight / 2).coerceAtLeast(0)
        val scrollableRange = (imgHeight - viewportHeight).coerceAtLeast(1)
        val percent = (targetScrollY * 100 / scrollableRange).coerceIn(0, 100)

        page.scrollToPercentage(percent)
        delay(POST_SCROLL_DELAY_MS)

        val actualScrollY = scrollYInImagePixels(page.getScrollState(), imgHeight)
        val viewportX = pageX
        val viewportY = pageY - actualScrollY

        val desc = action.reason.ifEmpty { "input at (${action.x},${action.y})" }

        logger.debug(
            "Full-page typing '{}' at normalized ({},{}) -> viewport ({},{}) - {} (reason: {})",
            action.text, action.x, action.y, viewportX, viewportY, desc, action.reason
        )

        state.previousClickScreenshot = rawScreenshotBytes

        try {
            page.clickAtCoordinates(viewportX, viewportY)
            delay(POST_CLICK_DELAY_MS)
            page.typeText(action.text)
            delay(POST_TYPE_DELAY_MS)
        } catch (e: CancellationException) {
            throw e
        }
        return ActionEffect.PAGE_CHANGED
    }

    private suspend fun handleViewportClick(
        action: NavigationAction.Click,
        page: IBrowserPage,
        state: NavigationLoopState,
        rawScreenshotBytes: ByteArray
    ): ActionEffect {
        val desc = action.target ?: "[${action.elementLabel}]"

        if (desc in state.offPageClickedDescs) {
            logger.debug("Skipping re-click on known off-page element '{}'", desc)
            updateLastActionOutcome(
                state,
                "Element '$desc' already navigates OFF this page. " +
                        "Do NOT click it again. Find the answer elsewhere on the CURRENT page, " +
                        "or use answer_found / give_up."
            )
            return ActionEffect.PAGE_UNCHANGED
        }

        val viewportX = action.resolvedCenterX
        val viewportY = action.resolvedCenterY

        if (viewportX == null || viewportY == null) {
            logger.warn("Could not resolve viewport click target for '{}' (label={}), attempting text-based fallback", desc, action.elementLabel)
            val labelText = action.target
            if (labelText != null) {
                val fallbackResult = attemptTextBasedClick(page, labelText, desc, state)
                if (fallbackResult != null) return fallbackResult
            }
            updateLastActionOutcome(state, "Could not locate element matching '$desc'.")
            return ActionEffect.PAGE_UNCHANGED
        }

        logger.debug(
            "Viewport click -> viewport ({},{}) - {} (reason: {})",
            viewportX, viewportY, desc, action.reason
        )

        state.previousClickScreenshot = rawScreenshotBytes

        val result = page.guardedClickAndCheckOverlay(viewportX, viewportY)
        if (result.navigatedAwayTo != null) {
            logger.info(
                "Click on '{}' intercepted navigation to {} — page unchanged",
                desc, result.navigatedAwayTo
            )
            val targetUrl = result.navigatedAwayTo!!
            state.discoveredUrls.add(targetUrl)
            state.onLinkDiscovered?.invoke(targetUrl)
            state.offPageClickedDescs.add(desc)
            state.previousClickScreenshot = null
            updateLastActionOutcome(state, buildOffPageOutcome(targetUrl))
            return ActionEffect.PAGE_UNCHANGED
        }

        val wasViewport = state.navigationMode == NavigationMode.VIEWPORT
        state.navigationMode = if (result.hasModalOverlay) NavigationMode.VIEWPORT else NavigationMode.FULL_PAGE
        if (state.navigationMode == NavigationMode.VIEWPORT && !wasViewport) {
            logger.info("Modal overlay detected after click — switching to viewport mode")
        } else if (state.navigationMode == NavigationMode.FULL_PAGE && wasViewport) {
            logger.info("Overlay dismissed after click — returning to full-page mode")
        }
        return ActionEffect.PAGE_CHANGED
    }

    private suspend fun handleViewportType(
        action: NavigationAction.Type,
        page: IBrowserPage,
        state: NavigationLoopState,
        imgWidth: Int,
        imgHeight: Int
    ): ActionEffect {
        val viewportX = toViewportCoord(action.x, imgWidth)
        val viewportY = toViewportCoord(action.y, imgHeight)

        val desc = action.reason.ifEmpty { "input at (${action.x},${action.y})" }

        logger.debug(
            "Viewport typing '{}' at normalized ({},{}) -> viewport ({},{}) - {} (reason: {})",
            action.text, action.x, action.y, viewportX, viewportY, desc, action.reason
        )

        page.clickAtCoordinates(viewportX, viewportY)
        delay(POST_CLICK_DELAY_MS)
        page.typeText(action.text)
        delay(POST_TYPE_DELAY_MS)
        return ActionEffect.PAGE_CHANGED
    }

    private fun resolveClicksByElementIndex(
        actions: List<NavigationAction>,
        elementIndex: Map<Int, AnnotatedElement>
    ): List<NavigationAction> = actions.map { action ->
        if (action is NavigationAction.Click) {
            val element = elementIndex[action.elementLabel]
            if (element != null) {
                action.copy(
                    resolvedCenterX = element.centerX,
                    resolvedCenterY = element.centerY,
                    target = action.target ?: element.text.take(80)
                )
            } else {
                logger.warn("Element label {} not found in index, click will use text fallback", action.elementLabel)
                action
            }
        } else action
    }


    private suspend fun scrollAndMapToViewport(
        page: IBrowserPage,
        pageX: Int,
        pageY: Int,
        fullPageImgHeight: Int,
        viewportHeight: Int
    ): Pair<Int, Int> {
        val targetScrollY = (pageY - viewportHeight / 2).coerceAtLeast(0)
        val scrollableRange = (fullPageImgHeight - viewportHeight).coerceAtLeast(1)
        val percent = (targetScrollY * 100 / scrollableRange).coerceIn(0, 100)
        val scroll = page.scrollToPercentageAndGetScrollState(percent)
        val actualScrollY = scrollYInImagePixels(scroll, fullPageImgHeight)
        return pageX to (pageY - actualScrollY)
    }

    /**
     * Convert a CSS-pixel scrollY into full-page-screenshot pixel space.
     * The two are 1:1 in the normal case; they diverge when the screenshot was
     * rendered at a non-1 device pixel ratio or capped below the page height,
     * so only ratios well away from 1.0 are treated as a real scale difference.
     */
    private fun scrollYInImagePixels(
        scroll: IBrowserPage.ScrollState,
        fullPageImgHeight: Int
    ): Int {
        val ratio = if (scroll.scrollHeight > 0) {
            fullPageImgHeight.toDouble() / scroll.scrollHeight
        } else 1.0
        val scale = if (abs(ratio - 1.0) > 0.02) ratio else 1.0
        if (scale != 1.0) {
            logger.debug(
                "Screenshot/CSS pixel scale {} (imgHeight={}, scrollHeight={})",
                scale, fullPageImgHeight, scroll.scrollHeight
            )
        }
        return (scroll.scrollY * scale).roundToInt()
    }

    private fun annotateScreenshot(
        rawScreenshot: IBrowserPage.Screenshot,
        elements: List<IBrowserPage.InteractiveElementInfo>
    ): Pair<IBrowserPage.Screenshot, Map<Int, AnnotatedElement>> {
        if (elements.isEmpty()) return Pair(rawScreenshot, emptyMap())

        val targets = elements.map { e ->
            AnnotationTarget(
                tag = e.tag, text = e.text, role = e.role, ariaLabel = e.ariaLabel,
                left = e.boundingBox.left, top = e.boundingBox.top,
                right = e.boundingBox.right, bottom = e.boundingBox.bottom,
                centerX = e.centerX, centerY = e.centerY,
                index = e.index
            )
        }

        val annotated = imageProcessingService.annotate(rawScreenshot.bytes, targets)

        val elementIndex = elements.associate { e ->
            e.index to AnnotatedElement(
                tag = e.tag, text = e.text, role = e.role, ariaLabel = e.ariaLabel,
                centerX = e.centerX, centerY = e.centerY, index = e.index
            )
        }

        return Pair(
            IBrowserPage.Screenshot(bytes = annotated.imageBytes, mimeType = ImageMimeType.JPEG),
            elementIndex
        )
    }

    private data class RegionCropMeta(val index: Int, val description: String)

    private data class ExtractionResult(
        val extractedContent: List<ExtractedContent>,
        val capturedImages: List<CapturedImage>,
        val regionCropMetas: List<RegionCropMeta> = emptyList()
    )

    private data class ExtractionPipelineResult(
        val regionLocatorTokenUsage: TokenUsageMetrics,
        val regionLocatorMs: Long,
        val extractionResult: ExtractionResult,
        val extractionMs: Long,
        val extractionObservation: String?,
        val extractedTextHashes: Set<String>,
        val capturedHashes: Set<String>
    )

    private data class IterationSetup(
        val screenshot: IBrowserPage.Screenshot,
        val labeledScreenshot: IBrowserPage.Screenshot,
        val elementIndex: Map<Int, AnnotatedElement>,
        val title: String,
        val interactiveElements: List<IBrowserPage.InteractiveElementInfo>,
        val imgWidth: Int,
        val imgHeight: Int,
        val fetchMs: Long,
        val annotateMs: Long,
        val screenshotWasCached: Boolean,
        val scrollStateHint: String?,
        val interactiveElementsHint: String?,
        val navigationMode: NavigationMode
    )

    private data class PreparedRegion(
        val region: IdentifiedRegion,
        val x: Int, val y: Int, val w: Int, val h: Int,
        val desc: String
    )

    private fun prepareRegions(
        regions: List<IdentifiedRegion>, origWidth: Int, origHeight: Int
    ): List<PreparedRegion> = regions.mapNotNull { region ->
        val x = ((region.x1 / 1000.0) * origWidth).toInt().coerceIn(0, origWidth - 1)
        val y = ((region.y1 / 1000.0) * origHeight).toInt().coerceIn(0, origHeight - 1)
        val x2 = ((region.x2 / 1000.0) * origWidth).toInt().coerceIn((x + 1).coerceAtMost(origWidth), origWidth)
        val y2 = ((region.y2 / 1000.0) * origHeight).toInt().coerceIn((y + 1).coerceAtMost(origHeight), origHeight)
        val w = x2 - x
        val h = y2 - y
        if (w <= 0 || h <= 0) return@mapNotNull null
        PreparedRegion(region, x, y, w, h, "${region.description} (${w}x${h})")
    }

    /**
     * Crops identified regions from the original screenshot (0-1000 normalized coords),
     * maps them to original-resolution pixel coords, and extracts content.
     * Text-only regions use DOM extraction (fast, no LLM); mixed/visual content falls
     * back to VLM. Table regions use [AgenticTableConversionAgent].
     */
    private suspend fun extractVisualSegmentationRegions(
        originalScreenshotBytes: ByteArray,
        origWidth: Int,
        origHeight: Int,
        regions: List<IdentifiedRegion>,
        query: String,
        extractedTextHashes: MutableSet<String>,
        capturedHashes: MutableSet<String>,
        page: IBrowserPage,
        navigationMode: NavigationMode,
        viewportHeight: Int,
        sessionId: SessionId = QuerySessionId("visual-segmentation"),
        iteration: Int = 0,
        url: String = ""
    ): ExtractionResult {
        val regionsToExtract = prepareRegions(regions, origWidth, origHeight)
        if (regionsToExtract.isEmpty()) return ExtractionResult(emptyList(), emptyList())

        val (tableRegions, textRegions) = regionsToExtract.partition { it.region.containsTable }

        val textHashes = mutableSetOf<String>()
        val textCapturedHashes = mutableSetOf<String>()
        val tableHashes = mutableSetOf<String>()
        val tableCapturedHashes = mutableSetOf<String>()

        val (textResult, tableResult) = coroutineScope {
            val textDeferred = async {
                extractTextRegions(
                    textRegions, originalScreenshotBytes, query, textHashes,
                    textCapturedHashes, MessageDigest.getInstance("SHA-256"),
                    page, navigationMode, viewportHeight, origHeight,
                    sessionId, iteration, url
                )
            }
            val tableDeferred = async {
                extractTableRegions(
                    tableRegions, originalScreenshotBytes, origWidth, origHeight, query,
                    tableHashes, tableCapturedHashes, MessageDigest.getInstance("SHA-256"),
                    page, navigationMode, viewportHeight, sessionId, iteration, url,
                    textRegions.size
                )
            }
            textDeferred.await() to tableDeferred.await()
        }

        extractedTextHashes.addAll(textHashes)
        extractedTextHashes.addAll(tableHashes)
        capturedHashes.addAll(textCapturedHashes)
        capturedHashes.addAll(tableCapturedHashes)

        return ExtractionResult(
            textResult.extractedContent + tableResult.extractedContent,
            textResult.capturedImages + tableResult.capturedImages,
            textResult.regionCropMetas + tableResult.regionCropMetas
        )
    }

    private suspend fun extractTextRegions(
        textRegions: List<PreparedRegion>,
        originalScreenshotBytes: ByteArray,
        query: String,
        extractedTextHashes: MutableSet<String>,
        capturedHashes: MutableSet<String>,
        sha256: MessageDigest,
        page: IBrowserPage,
        navigationMode: NavigationMode,
        viewportHeight: Int,
        fullPageImgHeight: Int,
        sessionId: SessionId,
        iteration: Int,
        url: String
    ): ExtractionResult {
        if (textRegions.isEmpty()) return ExtractionResult(emptyList(), emptyList())

        data class ProbedRegion(
            val prep: PreparedRegion,
            val idx: Int,
            val cropBytes: ByteArray,
            val domMarkdown: String?,
            val needsVlm: Boolean,
            val routeReason: String
        )

        val probed = mutableListOf<ProbedRegion>()
        for ((idx, prep) in textRegions.withIndex()) {
            val mark = TimeSource.Monotonic.markNow()
            val cropBytes = withContext(dispatcherProvider.default) {
                imageProcessingService.cropToPng(originalScreenshotBytes, prep.x, prep.y, prep.w, prep.h)
            }
            fireAndForget {
                iterationScreenshotStorage.saveRegionCrop(
                    sessionId, url, iteration, idx, cropBytes, prep.desc
                )
            }

            val vpCoords = toViewportCoords(
                prep.x, prep.y, prep.x + prep.w, prep.y + prep.h,
                navigationMode, viewportHeight, fullPageImgHeight, page
            )
            val regionContent = page.extractContentInRegion(vpCoords[0], vpCoords[1], vpCoords[2], vpCoords[3])

            val isTextOnly = regionContent.text.isNotBlank() && !regionContent.hasVisualContent
            if (isTextOnly) {
                val markdown = htmlToMarkdownService.convert(regionContent.html)
                val elapsedMs = mark.elapsedNow().inWholeMilliseconds
                logger.info(
                    "DOM extracted region ({} chars, {}ms): [{}]",
                    markdown.length, elapsedMs, prep.desc
                )
                probed.add(ProbedRegion(prep, idx, cropBytes, markdown, needsVlm = false, routeReason = "text-only"))
            } else {
                val reason = when {
                    regionContent.text.isBlank() -> "no DOM text"
                    regionContent.hasVisualContent -> "mixed content"
                    else -> "unknown"
                }
                logger.info("VLM needed for region ({}): [{}]", reason, prep.desc)
                probed.add(ProbedRegion(prep, idx, cropBytes, null, needsVlm = true, routeReason = reason))
            }
        }

        val vlmResults = coroutineScope {
            probed.filter { it.needsVlm }.map { pr ->
                async {
                    val mark = TimeSource.Monotonic.markNow()
                    val output = visualContentExtractionAgent.generate(
                        VisualContentExtractionInput(
                            regionImage = pr.cropBytes,
                            imageMimeType = ImageMimeType.PNG,
                            query = query,
                            regionDescription = pr.prep.region.relevance
                        )
                    )
                    val elapsedMs = mark.elapsedNow().inWholeMilliseconds
                    logger.info(
                        "VLM fallback region ({}, {}ms, {}x{} crop): [{}]",
                        pr.routeReason, elapsedMs, pr.prep.w, pr.prep.h, pr.prep.desc
                    )
                    pr.idx to output
                }
            }.awaitAll().associate { it }
        }

        val extracted = mutableListOf<ExtractedContent>()
        val images = mutableListOf<CapturedImage>()
        val regionCropMetas = mutableListOf<RegionCropMeta>()
        var totalVlmTokens: TokenUsageMetrics? = null

        for (pr in probed) {
            regionCropMetas.add(RegionCropMeta(pr.idx, pr.prep.region.description))
            addCapturedImage(sha256, pr.cropBytes, capturedHashes, images, pr.prep.region.relevance)

            val markdown: String
            if (!pr.needsVlm) {
                markdown = pr.domMarkdown!!
            } else {
                val output = vlmResults[pr.idx] ?: continue
                totalVlmTokens = if (totalVlmTokens == null) output.tokenUsage
                    else totalVlmTokens + output.tokenUsage
                markdown = output.markdown
            }

            if (markdown.isBlank()) {
                logger.debug("Blank extraction for region: {}", pr.prep.region.description)
                continue
            }
            if (addIfNotDuplicate(sha256, markdown, extractedTextHashes)) {
                extracted.add(
                    ExtractedContent(description = pr.prep.region.relevance, text = markdown, isTable = false)
                )
            }
        }

        if (totalVlmTokens != null) {
            tokenUsageService.recordTokenUsage(
                sessionId = sessionId,
                agentName = "VisualContentExtractionAgent",
                modelName = totalVlmTokens.modelName,
                promptTokens = totalVlmTokens.promptTokens,
                outputTokens = totalVlmTokens.outputTokens,
                totalTokens = totalVlmTokens.totalTokens
            )
        }

        return ExtractionResult(extracted, images, regionCropMetas)
    }

    private suspend fun extractTableRegions(
        tableRegions: List<PreparedRegion>,
        originalScreenshotBytes: ByteArray,
        origWidth: Int,
        origHeight: Int,
        query: String,
        extractedTextHashes: MutableSet<String>,
        capturedHashes: MutableSet<String>,
        sha256: MessageDigest,
        page: IBrowserPage,
        navigationMode: NavigationMode,
        viewportHeight: Int,
        sessionId: SessionId,
        iteration: Int,
        url: String,
        regionIndexOffset: Int
    ): ExtractionResult {
        if (tableRegions.isEmpty()) return ExtractionResult(emptyList(), emptyList())

        val extracted = mutableListOf<ExtractedContent>()
        val images = mutableListOf<CapturedImage>()
        val regionCropMetas = mutableListOf<RegionCropMeta>()
        var totalTokens = TokenUsageMetrics.empty("gemini-3.1-flash-lite")

        for ((tIdx, prep) in tableRegions.withIndex()) {
            val regionIdx = regionIndexOffset + tIdx
            regionCropMetas.add(RegionCropMeta(regionIdx, prep.region.description))

            val cropBytes = withContext(dispatcherProvider.default) {
                imageProcessingService.cropToPng(originalScreenshotBytes, prep.x, prep.y, prep.w, prep.h)
            }
            addCapturedImage(sha256, cropBytes, capturedHashes, images, prep.region.relevance)

            fireAndForget {
                iterationScreenshotStorage.saveRegionCrop(
                    sessionId, url, iteration, regionIdx, cropBytes, prep.desc
                )
            }

            val subRegionImages = if (prep.region.tableSubRegions.isNotEmpty()) {
                prep.region.tableSubRegions.mapNotNull { sr ->
                    val srX = ((sr.x1 / 1000.0) * origWidth).toInt().coerceIn(0, origWidth - 1)
                    val srY = ((sr.y1 / 1000.0) * origHeight).toInt().coerceIn(0, origHeight - 1)
                    val srX2 =
                        ((sr.x2 / 1000.0) * origWidth).toInt()
                            .coerceIn((srX + 1).coerceAtMost(origWidth), origWidth)
                    val srY2 =
                        ((sr.y2 / 1000.0) * origHeight).toInt()
                            .coerceIn((srY + 1).coerceAtMost(origHeight), origHeight)
                    val srW = srX2 - srX
                    val srH = srY2 - srY
                    if (srW <= 0 || srH <= 0) return@mapNotNull null
                    val srCropBytes = withContext(dispatcherProvider.default) {
                        imageProcessingService.cropToPng(originalScreenshotBytes, srX, srY, srW, srH)
                    }
                    TableSubRegionImage(
                        bytes = srCropBytes,
                        mimeType = ImageMimeType.PNG,
                        role = sr.role,
                        description = sr.description
                    )
                }
            } else {
                listOf(
                    TableSubRegionImage(
                        bytes = cropBytes,
                        mimeType = ImageMimeType.PNG,
                        role = TableRegionRole.DATA,
                        description = prep.region.relevance
                    )
                )
            }

            val vpCoords = toViewportCoords(
                prep.x, prep.y, prep.x + prep.w, prep.y + prep.h,
                navigationMode, viewportHeight, origHeight, page
            )
            val domHtml = page.extractContentInRegion(
                vpCoords[0].coerceAtLeast(0), vpCoords[1].coerceAtLeast(0),
                vpCoords[2].coerceAtLeast(vpCoords[0] + 1), vpCoords[3].coerceAtLeast(vpCoords[1] + 1)
            ).html

            logger.info(
                "Table extraction ({}x{} crop, {} sub-regions, {} chars DOM HTML): {}",
                prep.w, prep.h, subRegionImages.size, domHtml.length, prep.desc
            )

            val input = AgenticTableConversionInput(
                subRegionImages = subRegionImages,
                cleanedHtml = domHtml.takeIf { it.isNotBlank() },
                auxiliaryInfo = prep.region.relevance
            )
            val output = agenticTableConversionAgent.generate(input)
            totalTokens = totalTokens + output.tokenUsage

            if (output.htmlTable.isNotBlank()) {
                val markdown = TableMarkdownUtils.transformHTMLTablesToMarkdown(output.htmlTable)
                if (markdown.isNotBlank()) {
                    val normalizedMarkdown = normalizeText(markdown)
                    if (addIfNotDuplicate(sha256, normalizedMarkdown, extractedTextHashes)) {
                        extracted.add(
                            ExtractedContent(
                                description = prep.region.relevance,
                                text = normalizedMarkdown,
                                isTable = true
                            )
                        )
                        logger.info(
                            "Extracted table ({} chars): {}",
                            markdown.length, prep.region.relevance
                        )
                    }
                }
            }
        }

        tokenUsageService.recordTokenUsage(
            sessionId = sessionId,
            agentName = "AgenticTableConversionAgent",
            modelName = totalTokens.modelName,
            promptTokens = totalTokens.promptTokens,
            outputTokens = totalTokens.outputTokens,
            totalTokens = totalTokens.totalTokens
        )

        return ExtractionResult(extracted, images, regionCropMetas)
    }

    private suspend fun toViewportCoords(
        pageX1: Int, pageY1: Int, pageX2: Int, pageY2: Int,
        navigationMode: NavigationMode, viewportHeight: Int, fullPageImgHeight: Int,
        page: IBrowserPage
    ): List<Int> {
        if (navigationMode == NavigationMode.VIEWPORT) {
            return listOf(pageX1, pageY1, pageX2, pageY2)
        }
        val scrollableRange = (fullPageImgHeight - viewportHeight).coerceAtLeast(1)
        val regionCenterY = (pageY1 + pageY2) / 2
        val targetScrollY = (regionCenterY - viewportHeight / 2).coerceAtLeast(0)
        val percent = (targetScrollY * 100 / scrollableRange).coerceIn(0, 100)
        page.scrollToPercentage(percent)
        delay(POST_SCROLL_DELAY_MS)
        val actualScrollY = scrollYInImagePixels(page.getScrollState(), fullPageImgHeight)
        return listOf(pageX1, pageY1 - actualScrollY, pageX2, pageY2 - actualScrollY)
    }

    private fun addCapturedImage(
        sha256: MessageDigest,
        cropBytes: ByteArray,
        capturedHashes: MutableSet<String>,
        images: MutableList<CapturedImage>,
        relevance: String
    ) {
        val imageHash = sha256.digest(cropBytes).joinToString("") { "%02x".format(it) }
        if (imageHash !in capturedHashes) {
            capturedHashes.add(imageHash)
            images.add(
                CapturedImage(
                    bytes = cropBytes,
                    mimeType = "image/png",
                    relevance = relevance,
                    bytesHash = sha256.digest(cropBytes)
                )
            )
        }
    }

    private fun addIfNotDuplicate(
        sha256: MessageDigest,
        text: String,
        extractedTextHashes: MutableSet<String>
    ): Boolean {
        val textHash = sha256.digest(text.trim().replace("\\s+".toRegex(), " ").toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (textHash in extractedTextHashes) return false
        extractedTextHashes.add(textHash)
        return true
    }

    private fun applyVisualDiffAfterClick(
        currentScreenshotBytes: ByteArray,
        state: NavigationLoopState,
        currentDomSnapshot: IBrowserPage.DomSnapshot? = null
    ) {
        val previousScreenshot = state.previousClickScreenshot ?: return
        val changed = imageProcessingService.hasVisualChange(
            previousScreenshot, currentScreenshotBytes
        )
        val lastEntry = state.actionsPerformed.last()
        val clickDesc = when (val lastAction = lastEntry.action) {
            is NavigationAction.Click -> lastAction.target ?: "[${lastAction.elementLabel}]"

            is NavigationAction.Type -> lastAction.reason
                .ifEmpty { "(${lastAction.x},${lastAction.y})" }

            else -> ""
        }
        state.lastClickVisualChanged = changed
        if (changed) {
            val domBefore = state.previousDomSnapshot
            val outcome = if (domBefore != null && currentDomSnapshot != null) {
                val diff = domDiffService.diff(domBefore, currentDomSnapshot)
                "Page changed. ${diff.summary}"
            } else {
                "VISIBLE CHANGE on the page."
            }
            updateLastActionOutcome(state, outcome)
            state.failedClickDescs.remove(clickDesc)
        } else {
            val failCount = state.failedClickDescs.merge(clickDesc, 1, Int::plus)!!
            updateLastActionOutcome(
                state,
                if (failCount >= MAX_FAILED_CLICKS) {
                    "Element '$clickDesc' has FAILED $failCount times — it is NOT clickable. " +
                            "STOP trying it. Mark unanswerable questions as resolved and " +
                            "finish exploration with the EXTRACTED KNOWLEDGE you already have."
                } else {
                    "NO visible change. Try a different element or approach."
                }
            )
        }
        logger.debug("Visual diff after click: changed={}", changed)
    }

    // --- Action handlers ---

    private fun handleSearchComplete(
        state: NavigationLoopState,
        iteration: Int,
        timing: SearchTimingBreakdown? = null,
        terminationReason: TerminationReason = TerminationReason.ANSWER_FOUND
    ): AgenticPageSearchResult {
        val answer = state.extractedRegionContent
            .joinToString("\n\n") { "[${it.description}]${if (it.isTable) " (table)" else ""}:\n${it.text}" }
            .ifBlank { null }
        val observations = state.extractedRegionContent.map { "[${it.description}] ${it.text}" }

        val success = when (terminationReason) {
            TerminationReason.ANSWER_FOUND -> true
            else -> answer != null
        }

        logger.info(
            "Search complete after {} iterations ({} extracted, {} captures) for {} — reason={}",
            iteration, state.extractedRegionContent.size, state.capturedImages.size, state.url, terminationReason
        )

        return AgenticPageSearchResult(
            answer = answer,
            evidence = observations.lastOrNull(),
            contentDate = null,
            actionsPerformed = state.actionsPerformed,
            observations = observations,
            success = success,
            totalTokenUsage = state.aggregatedTokenUsage,
            discoveredUrls = state.discoveredUrls,
            capturedImages = state.capturedImages.toList(),
            timingBreakdown = timing,
            terminationReason = terminationReason
        )
    }

    private suspend fun handleScrollAt(
        action: NavigationAction.ScrollAt,
        page: IBrowserPage,
        state: NavigationLoopState,
        imgWidth: Int,
        imgHeight: Int
    ): ActionEffect {
        logger.debug(
            "ScrollAt: ({},{}) {} {}%",
            action.x,
            action.y,
            action.scrollDirection,
            action.scrollPercent
        )
        try {
            val viewportX = toViewportCoord(action.x, imgWidth)
            val viewportY = toViewportCoord(action.y, imgHeight)
            val (deltaX, deltaY) = computeScrollDeltas(
                action.scrollDirection,
                action.scrollPercent,
                imgWidth,
                imgHeight
            )
            page.scrollElementAtCoordinates(viewportX, viewportY, deltaX, deltaY)
            updateLastActionOutcome(
                state,
                "Scrolled container at (${action.x},${action.y}) ${action.scrollDirection.name.lowercase()} ${action.scrollPercent}%"
            )
            delay(POST_SCROLL_DELAY_MS)
        } catch (e: CancellationException) {
            throw e
        }
        return ActionEffect.PAGE_CHANGED
    }

    // --- Result building ---

    private fun buildFinalResult(
        state: NavigationLoopState,
        timing: SearchTimingBreakdown? = null,
        terminationReason: TerminationReason = TerminationReason.MAX_ITERATIONS
    ): AgenticPageSearchResult {
        val observations = state.extractedRegionContent.map { "[${it.description}] ${it.text}" }
        val answer = if (state.extractedRegionContent.isNotEmpty()) {
            observations.joinToString("; ")
        } else null

        val success = when (terminationReason) {
            TerminationReason.ANSWER_FOUND -> true
            else -> false
        }

        logger.info(
            "Agentic search terminated for {} ({} extracted, {} captures) — reason={}",
            state.url, state.extractedRegionContent.size, state.capturedImages.size, terminationReason
        )

        return AgenticPageSearchResult(
            answer = answer,
            evidence = observations.lastOrNull(),
            contentDate = null,
            actionsPerformed = state.actionsPerformed,
            observations = observations,
            success = success,
            totalTokenUsage = state.aggregatedTokenUsage,
            discoveredUrls = state.discoveredUrls,
            capturedImages = state.capturedImages.toList(),
            timingBreakdown = timing,
            terminationReason = terminationReason
        )
    }

    private fun buildOffPageOutcome(targetUrl: String): String =
        "Navigated OFF-PAGE to $targetUrl — recorded for separate investigation. " +
                "Look for the information elsewhere on the CURRENT page."

    // --- Cookie handling ---

    /**
     * Auto-dismiss cookie consent banners before the VLM navigation loop.
     *
     * Two-phase strategy:
     * 1. Batch-check for known CMP "Accept All" buttons and click the first match.
     * 2. Remove any remaining overlay/backdrop containers that block interaction.
     *
     * Costs 2-3 CDP round-trips (~10ms) instead of 1-2 VLM iterations (~2-4s + tokens).
     */
    private suspend fun dismissCookieBanner(page: IBrowserPage, url: String) {
        val existsMap = page.elementsExistByCssSelectors(COOKIE_ACCEPT_SELECTORS)
        val acceptSelector = existsMap.entries.firstOrNull { it.value }?.key

        if (acceptSelector != null) {
            try {
                page.clickByCssSelector(acceptSelector)
                delay(COOKIE_DISMISS_DELAY_MS)
                logger.debug("Dismissed cookie banner via accept click on {}: {}", url, acceptSelector)
            } catch (e: Exception) {
                logger.debug("Accept button click failed on {} ({}), falling back to removal", url, e.message)
            }
        }

        page.removeElementsByCssSelectors(COOKIE_OVERLAY_SELECTORS)
    }

    // --- Iteration metadata persistence ---

    private fun buildIterationEntity(
        sessionId: SessionId,
        url: String,
        iteration: Int,
        navOutput: FullPageNavigationOutput,
        mimeType: ImageMimeType,
        durationMs: Long,
        regionCropMetas: List<RegionCropMeta> = emptyList()
    ): AgenticNavIteration {
        val screenshots = mutableListOf(
            ScreenshotRecord(ScreenshotType.RAW, IterationScreenshotPath.rawPath(sessionId, url, iteration, mimeType)),
            ScreenshotRecord(
                ScreenshotType.ANNOTATED,
                IterationScreenshotPath.annotatedPath(sessionId, url, iteration)
            ),
        )
        for (meta in regionCropMetas) {
            screenshots.add(
                ScreenshotRecord(
                    ScreenshotType.REGION_CROP,
                    IterationScreenshotPath.regionCropPath(sessionId, url, iteration, meta.index),
                    regionIndex = meta.index,
                    description = meta.description
                )
            )
        }
        val assessment = navOutput.continuationAssessment
        val decision = when {
            navOutput.searchComplete && assessment?.answerFound?.satisfied == true -> "answer_found"
            navOutput.searchComplete && assessment?.pageRelevant?.satisfied == false -> "page_irrelevant"
            navOutput.searchComplete && assessment?.unexploredPotential?.satisfied == false -> "no_potential"
            navOutput.searchComplete -> "search_complete"
            navOutput.actions.isEmpty() -> "direction_switch"
            else -> "continue_exploring"
        }
        return AgenticNavIteration(
            sessionId = sessionId,
            url = url,
            iteration = iteration,
            observation = navOutput.observation,
            decision = decision,
            actionsJson = actionSerializer.encodeToString(
                ListSerializer(NavigationAction.serializer()),
                navOutput.actions
            ),
            screenshots = screenshots,
            durationMs = durationMs,
            createdAtEpochMs = System.currentTimeMillis()
        )
    }

    private suspend fun saveIterationMetadata(
        sessionId: SessionId,
        url: String,
        iteration: Int,
        navOutput: FullPageNavigationOutput,
        mimeType: ImageMimeType,
        durationMs: Long,
        regionCropMetas: List<RegionCropMeta> = emptyList()
    ) {
        try {
            val entity =
                buildIterationEntity(sessionId, url, iteration, navOutput, mimeType, durationMs, regionCropMetas)
            agenticNavIterationRepository.save(entity)
        } catch (e: Exception) {
            logger.warn(
                "Failed to persist iteration metadata (session={}, iter={}): {}",
                sessionId.value,
                iteration,
                e.message
            )
        }
    }

    // --- Utilities ---

    private fun toViewportCoord(normalized: Int, dimension: Int): Int =
        ((normalized / 1000.0) * dimension).toInt()

    private fun computeScrollDeltas(
        direction: ScrollDirection,
        percent: Int,
        imgWidth: Int,
        imgHeight: Int
    ): Pair<Int, Int> {
        val isHorizontal = direction == ScrollDirection.LEFT || direction == ScrollDirection.RIGHT
        val dimension = if (isHorizontal) imgWidth else imgHeight
        val scrollPx = (dimension * percent / 100.0).toInt()
        return when (direction) {
            ScrollDirection.DOWN -> 0 to scrollPx
            ScrollDirection.UP -> 0 to -scrollPx
            ScrollDirection.RIGHT -> scrollPx to 0
            ScrollDirection.LEFT -> -scrollPx to 0
        }
    }

    private fun updateLastActionOutcome(state: NavigationLoopState, outcome: String) {
        state.actionsPerformed[state.actionsPerformed.lastIndex] =
            state.actionsPerformed.last().copy(outcome = outcome)
    }

}
