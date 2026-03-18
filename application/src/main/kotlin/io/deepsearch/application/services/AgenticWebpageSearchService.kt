package io.deepsearch.application.services

import io.deepsearch.domain.agents.ActionWithOutcome
import io.deepsearch.domain.agents.CaptureRegion
import io.deepsearch.domain.agents.IWebpageNavigationAgent
import io.deepsearch.domain.agents.NavigationAction
import io.deepsearch.domain.agents.ScrollDirection
import io.deepsearch.domain.agents.SearchKeywordsResult
import io.deepsearch.domain.agents.TrackedQuestion
import io.deepsearch.domain.agents.WebpageNavigationInput
import io.deepsearch.domain.agents.WebpageNavigationOutput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.browser.ScrollToTextResult
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.services.IImageProcessingService
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

data class CapturedImage(
    val bytes: ByteArray,
    val mimeType: String,
    val relevance: String,
    val bytesHash: ByteArray
)

data class AgenticPageSearchResult(
    val answer: String?,
    val evidence: String?,
    val contentDate: String?,
    val actionsPerformed: List<ActionWithOutcome>,
    val observations: List<String>,
    val success: Boolean,
    val totalTokenUsage: TokenUsageMetrics,
    val discoveredUrls: List<String> = emptyList(),
    val capturedImages: List<CapturedImage> = emptyList()
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
    private val webpageNavigationAgent: IWebpageNavigationAgent,
    private val imageProcessingService: IImageProcessingService,
    private val tokenUsageService: ILlmTokenUsageService,
    private val pageTextSearchService: IPageTextSearchService
) : IAgenticWebpageSearchService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val MAX_ITERATIONS = 12
        private const val MAX_FAILED_CLICKS = 2
        private const val POST_CLICK_DELAY_MS = 350L
        private const val POST_SCROLL_DELAY_MS = 150L
        private const val POST_TYPE_DELAY_MS = 500L
        private const val PEEK_MAX_HEIGHT = 3000
        private const val PEEK_JPEG_QUALITY = 0.80f
        private const val MIN_CAPTURE_SIZE_PX = 40
        private const val COOKIE_DISMISS_DELAY_MS = 300L

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

    private data class FindOnPageResult(val outcome: String, val autoScrolled: Boolean)

    private data class IterationContext(
        val screenshot: IBrowserPage.Screenshot,
        val pageTitle: String,
        val pageDescription: String?,
        val scrollPercent: Int
    )

    private class NavigationLoopState(
        val url: String,
        val query: String,
        val sessionId: SessionId,
        val onLinkDiscovered: (suspend (String) -> Unit)?,
        val actionsPerformed: MutableList<ActionWithOutcome> = mutableListOf(),
        var trackedQuestions: List<TrackedQuestion> = emptyList(),
        var generalFindings: List<String> = emptyList(),
        val discoveredUrls: MutableList<String> = mutableListOf(),
        val capturedImages: MutableList<CapturedImage> = mutableListOf(),
        val capturedHashes: MutableSet<String> = mutableSetOf(),
        var aggregatedTokenUsage: TokenUsageMetrics = TokenUsageMetrics.empty("gemini-3.1-flash-lite"),
        var lastFindCounts: Map<String, IBrowserPage.TextMatchCounts> = emptyMap(),
        var previousClickScreenshot: ByteArray? = null,
        val failedClickDescs: MutableMap<String, Int> = mutableMapOf(),
        val offPageClickedDescs: MutableSet<String> = mutableSetOf(),
        var lastActionWasClick: Boolean = false,
        var peekScreenshotOverride: IBrowserPage.Screenshot? = null,
        var lastCtx: IterationContext? = null,
        var skipContextFetch: Boolean = false
    )

    // --- Public API ---

    override suspend fun searchWithinPage(
        url: String,
        query: String,
        sessionId: SessionId
    ): AgenticPageSearchResult {
        logger.info("Starting agentic page search (own page) for query='{}' on url={}", query, url)

        return coroutineScope {
            val keywordsDeferred = async {
                try {
                    webpageNavigationAgent.generateSearchKeywords(query)
                } catch (e: CancellationException) {
                    throw e
                }
            }
            browserPool.withPage { page ->
                page.navigate(url)
                page.waitForLoad()
                val keywordsResult = keywordsDeferred.await()
                doSearchWithinPage(page, url, query, sessionId, preScanKeywords = keywordsResult)
            }
        }
    }

    override suspend fun searchWithinPage(
        page: IBrowserPage,
        url: String,
        query: String,
        sessionId: SessionId,
        onLinkDiscovered: (suspend (String) -> Unit)?
    ): AgenticPageSearchResult = doSearchWithinPage(page, url, query, sessionId, onLinkDiscovered)

    // --- Core navigation loop ---

    private suspend fun doSearchWithinPage(
        page: IBrowserPage,
        url: String,
        query: String,
        sessionId: SessionId,
        onLinkDiscovered: (suspend (String) -> Unit)? = null,
        preScanKeywords: SearchKeywordsResult? = null
    ): AgenticPageSearchResult {
        logger.info("Starting agentic page search for query='{}' on url={}", query, url)

        dismissCookieBanner(page, url)

        val state = NavigationLoopState(
            url = url,
            query = query,
            sessionId = sessionId,
            onLinkDiscovered = onLinkDiscovered
        )

        if (preScanKeywords != null && preScanKeywords.keywords.isNotEmpty()) {
            executePreScan(page, preScanKeywords, state)
        }

        for (iteration in 1..MAX_ITERATIONS) {
            currentCoroutineContext().ensureActive()
            logger.debug("Agentic search iteration {}/{} for {}", iteration, MAX_ITERATIONS, url)

            val (ctx, _) = fetchIterationContext(page, state)
            state.lastCtx = ctx

            if (state.previousClickScreenshot != null && state.lastActionWasClick) {
                applyVisualDiffAfterClick(ctx.screenshot.bytes, state)
            }
            state.lastActionWasClick = false

            val screenshotForAgent = state.peekScreenshotOverride ?: IBrowserPage.Screenshot(
                bytes = ctx.screenshot.bytes,
                mimeType = ctx.screenshot.mimeType
            )
            state.peekScreenshotOverride = null

            val input = WebpageNavigationInput(
                screenshot = screenshotForAgent,
                query = query,
                previousActions = state.actionsPerformed.toList(),
                questions = state.trackedQuestions,
                generalFindings = state.generalFindings,
                pageUrl = url,
                pageTitle = ctx.pageTitle,
                pageDescription = ctx.pageDescription,
                scrollPercent = ctx.scrollPercent,
                currentIteration = iteration,
                maxIterations = MAX_ITERATIONS
            )

            val output = webpageNavigationAgent.generate(input)
            state.aggregatedTokenUsage = state.aggregatedTokenUsage + output.tokenUsage

            tokenUsageService.recordTokenUsage(
                sessionId = sessionId,
                agentName = "WebpageNavigationAgent",
                modelName = output.tokenUsage.modelName,
                promptTokens = output.tokenUsage.promptTokens,
                outputTokens = output.tokenUsage.outputTokens,
                totalTokens = output.tokenUsage.totalTokens
            )

            val newFindings = updateFindings(output, state)

            if (output.captureRegions.isNotEmpty()) {
                cropCaptureRegions(
                    ctx.screenshot.bytes,
                    output.captureRegions,
                    state.capturedImages,
                    state.capturedHashes
                )
            }

            val (imgWidth, imgHeight) = imageProcessingService.getImageDimensions(ctx.screenshot.bytes)

            val clickTargets: Map<Int, IBrowserPage.ElementAtPoint> =
                if (hasConsecutiveClicks(output.actions)) {
                    val clickCoords = output.actions.mapIndexedNotNull { idx, act ->
                        if (act is NavigationAction.Click)
                            idx to (toViewportCoord(act.x, imgWidth) to toViewportCoord(act.y, imgHeight))
                        else null
                    }
                    val results = page.getElementsAtPoints(clickCoords.map { it.second })
                    buildMap {
                        clickCoords.zip(results).forEach { (idxCoord, el) ->
                            if (el != null) put(idxCoord.first, el)
                        }
                    }
                } else emptyMap()

            var pageChangedThisIteration = false
            var alreadyContinuedClick = false
            for ((actionIdx, action) in output.actions.withIndex()) {
                state.actionsPerformed.add(
                    if (actionIdx == 0) ActionWithOutcome(
                        action,
                        observation = output.observation,
                        findings = newFindings
                    )
                    else ActionWithOutcome(action, observation = output.observation)
                )

                when (action) {
                    is NavigationAction.ExplorationFinished ->
                        return handleExplorationFinished(action, state, newFindings, iteration)

                    else -> {
                        val effect = when (action) {
                            is NavigationAction.Click ->
                                handleClick(action, page, state, ctx.screenshot.bytes, imgWidth, imgHeight)
                            is NavigationAction.FindOnPage ->
                                handleFindOnPage(action, page, state)
                            is NavigationAction.ScrollToText ->
                                handleScrollToText(action, page, state)
                            is NavigationAction.Scroll ->
                                handleScroll(action, page, state, imgWidth, imgHeight)
                            is NavigationAction.ScrollAt ->
                                handleScrollAt(action, page, state, imgWidth, imgHeight)
                            is NavigationAction.PeekFullPage ->
                                handlePeekFullPage(page, state)
                            is NavigationAction.Type ->
                                handleType(action, page, state, ctx.screenshot.bytes, imgWidth, imgHeight)
                        }
                        if (effect == ActionEffect.PAGE_CHANGED) {
                            pageChangedThisIteration = true

                            if (action is NavigationAction.Click && !alreadyContinuedClick) {
                                val nextIdx = actionIdx + 1
                                val nextAction = output.actions.getOrNull(nextIdx)
                                val expectedTarget = clickTargets[nextIdx]

                                if (nextAction is NavigationAction.Click && expectedTarget != null) {
                                    delay(POST_CLICK_DELAY_MS)
                                    val nextVx = toViewportCoord(nextAction.x, imgWidth)
                                    val nextVy = toViewportCoord(nextAction.y, imgHeight)
                                    val fresh = page.getElementsAtPoints(listOf(nextVx to nextVy)).firstOrNull()

                                    if (fresh != null && fresh.path == expectedTarget.path) {
                                        alreadyContinuedClick = true
                                        updateLastActionOutcome(state, "Executed; continued to next action.")
                                        logger.debug(
                                            "Click continuation verified: <{}> '{}' [{}] still at ({},{})",
                                            fresh.tag, fresh.text.take(30), fresh.path, nextVx, nextVy
                                        )
                                        continue
                                    } else {
                                        logger.debug(
                                            "Click continuation blocked at ({},{}): expected [{}], found [{}]",
                                            nextVx, nextVy,
                                            expectedTarget.path,
                                            fresh?.path ?: "null"
                                        )
                                    }
                                }
                            }
                            break
                        }
                    }
                }
            }
            state.skipContextFetch = output.actions.isNotEmpty() && !pageChangedThisIteration
        }

        return buildFinalResult(state)
    }

    // --- Pre-scan ---

    private suspend fun executePreScan(
        page: IBrowserPage,
        preScanKeywords: SearchKeywordsResult,
        state: NavigationLoopState
    ) {
        state.aggregatedTokenUsage = state.aggregatedTokenUsage + preScanKeywords.tokenUsage
        tokenUsageService.recordTokenUsage(
            sessionId = state.sessionId,
            agentName = "WebpageNavigationAgent.keywords",
            modelName = preScanKeywords.tokenUsage.modelName,
            promptTokens = preScanKeywords.tokenUsage.promptTokens,
            outputTokens = preScanKeywords.tokenUsage.outputTokens,
            totalTokens = preScanKeywords.tokenUsage.totalTokens
        )

        try {
            val keywords = preScanKeywords.keywords
            val result = executeFindOnPage(page, keywords, state)

            state.actionsPerformed.add(
                ActionWithOutcome(
                    NavigationAction.FindOnPage(keywords = keywords, reason = "pre-scan"),
                    outcome = result.outcome,
                    observation = "Pre-scan keyword search before first visual analysis."
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Pre-scan find_on_page failed: {}", e.message)
        }
    }

    // --- Iteration helpers ---

    private suspend fun fetchIterationContext(
        page: IBrowserPage,
        state: NavigationLoopState
    ): Pair<IterationContext, Boolean> {
        if (state.skipContextFetch && state.lastCtx != null) {
            logger.debug("Reusing previous context for {} (no page change last round)", state.url)
            return Pair(state.lastCtx!!, false)
        }

        val ctx = coroutineScope {
            val screenshotDeferred = async { page.takeScreenshot() }
            val titleDeferred = async { page.getTitle() }
            val descDeferred = async { page.getDescription() }
            val scrollDeferred = async { page.getScrollPosition() }
            IterationContext(
                screenshot = screenshotDeferred.await(),
                pageTitle = titleDeferred.await(),
                pageDescription = descDeferred.await(),
                scrollPercent = scrollDeferred.await()
            )
        }
        return Pair(ctx, true)
    }

    private fun updateFindings(
        output: WebpageNavigationOutput,
        state: NavigationLoopState
    ): List<String> {
        val previousAllFacts = buildSet {
            state.trackedQuestions.flatMapTo(this) { it.findings }
            addAll(state.generalFindings)
        }
        state.trackedQuestions = output.questionsState
        state.generalFindings = output.generalFindings
        val currentAllFacts = buildSet {
            output.questionsState.flatMapTo(this) { it.findings }
            addAll(output.generalFindings)
        }
        val newFindings = (currentAllFacts - previousAllFacts).toList()

        if (newFindings.isNotEmpty()) {
            logger.info(
                "Agentic search new findings ({}) for {}: {}",
                newFindings.size, state.url, newFindings.joinToString("; ") { it.take(80) }
            )
        }

        return newFindings
    }

    private fun applyVisualDiffAfterClick(
        currentScreenshotBytes: ByteArray,
        state: NavigationLoopState
    ) {
        val previousScreenshot = state.previousClickScreenshot ?: return
        val changed = imageProcessingService.hasVisualChange(
            previousScreenshot, currentScreenshotBytes
        )
        val lastEntry = state.actionsPerformed.last()
        val clickDesc = when (val lastAction = lastEntry.action) {
            is NavigationAction.Click -> lastAction.reason.take(60)
                .ifEmpty { "(${lastAction.x},${lastAction.y})" }

            is NavigationAction.Type -> lastAction.reason.take(60)
                .ifEmpty { "(${lastAction.x},${lastAction.y})" }

            else -> ""
        }
        if (changed) {
            updateLastActionOutcome(state, "VISIBLE CHANGE on the page.")
            state.failedClickDescs.remove(clickDesc)
        } else {
            val failCount = state.failedClickDescs.merge(clickDesc, 1, Int::plus)!!
            updateLastActionOutcome(
                state,
                if (failCount >= MAX_FAILED_CLICKS) {
                    "Element '$clickDesc' has FAILED $failCount times — it is NOT clickable. " +
                            "STOP trying it. Mark unanswerable questions as resolved and " +
                            "use answer_found with the findings you already have."
                } else {
                    "NO visible change. Try a different element or approach."
                }
            )
        }
        logger.debug("Visual diff after click: changed={}", changed)
    }

    // --- Action handlers ---

    private fun handleExplorationFinished(
        action: NavigationAction.ExplorationFinished,
        state: NavigationLoopState,
        newFindings: List<String>,
        iteration: Int
    ): AgenticPageSearchResult {
        val allObservations = collectAllFindings(state.trackedQuestions, state.generalFindings)
        val answer = action.answer
        if (answer != null) {
            logger.info(
                "Agentic search finished with answer after {} iterations ({} findings, {} captures) for {}: {}",
                iteration, allObservations.size, state.capturedImages.size, state.url, answer.take(100)
            )
            return AgenticPageSearchResult(
                answer = answer,
                evidence = newFindings.lastOrNull() ?: allObservations.lastOrNull(),
                contentDate = action.contentDate,
                actionsPerformed = state.actionsPerformed,
                observations = allObservations,
                success = true,
                totalTokenUsage = state.aggregatedTokenUsage,
                discoveredUrls = state.discoveredUrls,
                capturedImages = state.capturedImages.toList()
            )
        }
        logger.info(
            "Agentic search finished with no findings after {} iterations for {}",
            iteration, state.url
        )
        return AgenticPageSearchResult(
            answer = null,
            evidence = null,
            contentDate = null,
            actionsPerformed = state.actionsPerformed,
            observations = allObservations,
            success = false,
            totalTokenUsage = state.aggregatedTokenUsage,
            discoveredUrls = state.discoveredUrls,
            capturedImages = state.capturedImages.toList()
        )
    }

    private suspend fun handleClick(
        action: NavigationAction.Click,
        page: IBrowserPage,
        state: NavigationLoopState,
        rawScreenshotBytes: ByteArray,
        imgWidth: Int,
        imgHeight: Int
    ): ActionEffect {
        val viewportX = toViewportCoord(action.x, imgWidth)
        val viewportY = toViewportCoord(action.y, imgHeight)
        val desc = action.reason.take(60).ifEmpty { "element at (${action.x},${action.y})" }

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

        logger.debug(
            "Click normalized ({},{}) -> viewport ({},{}) - {} (reason: {})",
            action.x, action.y, viewportX, viewportY, desc, action.reason
        )

        state.previousClickScreenshot = rawScreenshotBytes

        try {
            val clickResult = page.guardedClickAtCoordinates(viewportX, viewportY)
            if (clickResult.navigatedAwayTo != null) {
                logger.info(
                    "Click on '{}' intercepted navigation to {} — page unchanged",
                    desc, clickResult.navigatedAwayTo
                )
                val targetUrl = clickResult.navigatedAwayTo!!
                state.discoveredUrls.add(targetUrl)
                state.onLinkDiscovered?.invoke(targetUrl)
                state.offPageClickedDescs.add(desc)
                state.lastActionWasClick = false
                updateLastActionOutcome(state, buildOffPageOutcome(targetUrl))
                return ActionEffect.PAGE_UNCHANGED
            } else {
                state.lastActionWasClick = true
            }
        } catch (e: CancellationException) {
            throw e
        }

        return ActionEffect.PAGE_CHANGED
    }

    private suspend fun handleFindOnPage(
        action: NavigationAction.FindOnPage,
        page: IBrowserPage,
        state: NavigationLoopState
    ): ActionEffect {
        logger.debug("FindOnPage: keywords={}", action.keywords)
        try {
            if (action.keywords.isEmpty()) {
                updateLastActionOutcome(state, "No keywords provided. Provide keywords to search for.")
                return ActionEffect.PAGE_UNCHANGED
            }
            val result = executeFindOnPage(page, action.keywords, state)
            updateLastActionOutcome(state, result.outcome)
            return if (result.autoScrolled) ActionEffect.PAGE_CHANGED else ActionEffect.PAGE_UNCHANGED
        } catch (e: CancellationException) {
            throw e
        }
    }

    private suspend fun handleScrollToText(
        action: NavigationAction.ScrollToText,
        page: IBrowserPage,
        state: NavigationLoopState
    ): ActionEffect {
        val dirName = action.direction.name
        logger.debug("ScrollToText: text='{}', direction={}", action.searchText, dirName)
        try {
            if (action.searchText.isBlank()) {
                updateLastActionOutcome(state, "No searchText provided.")
                return ActionEffect.PAGE_UNCHANGED
            }
            val result = page.scrollToTextInDirection(action.searchText, dirName)
            val totalMatches = state.lastFindCounts[action.searchText]?.total ?: 0
            when (result) {
                ScrollToTextResult.SCROLLED_TO_VISIBLE -> {
                    logger.info("scroll_to_text found '{}' {} on {}", action.searchText, dirName, state.url)
                    val outcomeText = buildString {
                        append("Scrolled ${dirName.lowercase()} to \"${action.searchText}\".")
                        if (totalMatches > 1) append(" There are $totalMatches total matches on this page.")
                    }
                    updateLastActionOutcome(state, outcomeText)
                    delay(POST_SCROLL_DELAY_MS)
                    return ActionEffect.PAGE_CHANGED
                }
                ScrollToTextResult.SCROLLED_TO_HIDDEN_ANCESTOR -> {
                    logger.info("scroll_to_text: '{}' is hidden, scrolled to visible ancestor on {}", action.searchText, state.url)
                    updateLastActionOutcome(state,
                        "\"${action.searchText}\" is inside a hidden/collapsed section. Scrolled to the nearest visible container. Look for an accordion, tab, or toggle to expand and reveal the content.")
                    delay(POST_SCROLL_DELAY_MS)
                    return ActionEffect.PAGE_CHANGED
                }
                ScrollToTextResult.HIDDEN_ALREADY_NEARBY -> {
                    logger.info("scroll_to_text: '{}' is hidden but already nearby on {}", action.searchText, state.url)
                    updateLastActionOutcome(state,
                        "\"${action.searchText}\" is inside a hidden/collapsed section already in view. Look for an accordion, tab, or toggle nearby to expand and reveal the content.")
                    return ActionEffect.PAGE_UNCHANGED
                }
                ScrollToTextResult.NOT_FOUND -> {
                    logger.debug("scroll_to_text: '{}' not found {} on {}", action.searchText, dirName, state.url)
                    updateLastActionOutcome(state, "No occurrence of \"${action.searchText}\" found ${dirName.lowercase()} from current viewport.")
                    return ActionEffect.PAGE_UNCHANGED
                }
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    private suspend fun handleScroll(
        action: NavigationAction.Scroll,
        page: IBrowserPage,
        state: NavigationLoopState,
        imgWidth: Int,
        imgHeight: Int
    ): ActionEffect {
        logger.debug("Scroll: {} {}%", action.scrollDirection, action.scrollPercent)
        try {
            val (deltaX, deltaY) = computeScrollDeltas(
                action.scrollDirection,
                action.scrollPercent,
                imgWidth,
                imgHeight
            )
            page.scrollPage(deltaX, deltaY)
            val scrollOutcome = buildString {
                append("Scrolled ${action.scrollDirection.name.lowercase()} ${action.scrollPercent}%")
            }
            updateLastActionOutcome(state, scrollOutcome)
            delay(POST_SCROLL_DELAY_MS)
        } catch (e: CancellationException) {
            throw e
        }
        return ActionEffect.PAGE_CHANGED
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

    private suspend fun handlePeekFullPage(
        page: IBrowserPage,
        state: NavigationLoopState
    ): ActionEffect {
        logger.debug("Taking full-page peek screenshot for {}", state.url)
        try {
            val fullPageScreenshot = page.takeFullPageScreenshot()
            val downscaled = downscaleScreenshot(fullPageScreenshot.bytes, PEEK_MAX_HEIGHT)
            state.peekScreenshotOverride = IBrowserPage.Screenshot(
                bytes = downscaled,
                mimeType = ImageMimeType.JPEG
            )
            updateLastActionOutcome(
                state,
                "Full page overview captured. Study this image to understand " +
                        "the overall page content and layout, then decide your next action."
            )
        } catch (e: CancellationException) {
            throw e
        }
        return ActionEffect.PAGE_CHANGED
    }

    private suspend fun handleType(
        action: NavigationAction.Type,
        page: IBrowserPage,
        state: NavigationLoopState,
        rawScreenshotBytes: ByteArray,
        imgWidth: Int,
        imgHeight: Int
    ): ActionEffect {
        val viewportX = toViewportCoord(action.x, imgWidth)
        val viewportY = toViewportCoord(action.y, imgHeight)
        val desc = action.reason.take(60).ifEmpty { "input at (${action.x},${action.y})" }

        logger.debug(
            "Typing '{}' at normalized ({},{}) -> viewport ({},{}) - {} (reason: {})",
            action.text.take(30), action.x, action.y, viewportX, viewportY, desc, action.reason
        )

        state.previousClickScreenshot = rawScreenshotBytes
        state.lastActionWasClick = true

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

    // --- Find-on-page logic (shared by pre-scan and in-loop FindOnPage action) ---

    /**
     * Runs parallel exact + stemmed text search, builds the outcome string,
     * and auto-scrolls to the best match if one exists.
     */
    private suspend fun executeFindOnPage(
        page: IBrowserPage,
        keywords: List<String>,
        state: NavigationLoopState
    ): FindOnPageResult {
        val (counts, stemmedMatches) = coroutineScope {
            val countsDeferred = async { page.countTextMatches(keywords) }
            val stemmedDeferred = async {
                try {
                    val pageText = page.extractTextContent()
                    pageTextSearchService.search(pageText, keywords)
                } catch (e: Exception) {
                    logger.debug("Stemmed text search failed, proceeding with exact only: {}", e.message)
                    emptyMap()
                }
            }
            Pair(countsDeferred.await(), stemmedDeferred.await())
        }
        state.lastFindCounts = state.lastFindCounts + counts

        val countsDesc = formatCountsDescription(counts)
        logger.info("find_on_page results for {}: {}", state.url, countsDesc)

        val outcome = buildFindOnPageOutcome(counts, stemmedMatches, keywords, countsDesc)

        val scrollTarget = pickAutoScrollTarget(counts, stemmedMatches, keywords)
        if (scrollTarget != null) {
            val scrollResult = page.scrollToTextContent(scrollTarget)
            when (scrollResult) {
                ScrollToTextResult.SCROLLED_TO_VISIBLE -> {
                    logger.info("find_on_page auto-scrolled to '{}' on {}", scrollTarget, state.url)
                    delay(POST_SCROLL_DELAY_MS)
                    return FindOnPageResult("$outcome Auto-scrolled to \"$scrollTarget\".", autoScrolled = true)
                }
                ScrollToTextResult.SCROLLED_TO_HIDDEN_ANCESTOR -> {
                    logger.info("find_on_page auto-scrolled to hidden text's ancestor for '{}' on {}", scrollTarget, state.url)
                    delay(POST_SCROLL_DELAY_MS)
                    return FindOnPageResult(
                        "$outcome \"$scrollTarget\" is inside a hidden/collapsed section. Auto-scrolled to its nearest visible container. Look for an accordion, tab, or toggle to expand.",
                        autoScrolled = true
                    )
                }
                ScrollToTextResult.HIDDEN_ALREADY_NEARBY -> {
                    logger.info("find_on_page: hidden text '{}' already nearby on {}", scrollTarget, state.url)
                    return FindOnPageResult(
                        "$outcome \"$scrollTarget\" is inside a hidden/collapsed section already in view. Look for an accordion, tab, or toggle nearby to expand.",
                        autoScrolled = false
                    )
                }
                ScrollToTextResult.NOT_FOUND -> {
                    delay(POST_SCROLL_DELAY_MS)
                }
            }
        }

        return FindOnPageResult(outcome, autoScrolled = false)
    }

    private val priceKeywords = setOf("price", "cost", "pricing", "fee", "fees", "rate")

    /**
     * Picks the best keyword to auto-scroll to after find_on_page.
     * Priority: visible match > hidden-only match (navigable via ancestor) > stemmed match.
     * Returns null if no suitable scroll target exists.
     */
    internal fun pickAutoScrollTarget(
        counts: Map<String, IBrowserPage.TextMatchCounts>,
        stemmedMatches: Map<String, List<TextMatch>>,
        keywords: List<String>
    ): String? {
        val firstVisibleExact = keywords.firstOrNull { kw ->
            counts[kw]?.visible?.let { it > 0 } == true
        }
        if (firstVisibleExact != null) return firstVisibleExact

        val firstHiddenOnly = keywords.firstOrNull { kw ->
            val c = counts[kw] ?: return@firstOrNull false
            c.visible == 0 && c.total > 0
        }
        if (firstHiddenOnly != null) return firstHiddenOnly

        val bestStemmed = stemmedMatches.values.flatten().maxByOrNull { it.score }
        return bestStemmed?.matchedText?.take(60)
    }

    internal fun buildFindOnPageOutcome(
        counts: Map<String, IBrowserPage.TextMatchCounts>,
        stemmedMatches: Map<String, List<TextMatch>>,
        keywords: List<String>,
        countsDesc: String
    ): String = buildString {
        append("Match counts — $countsDesc.")

        val hiddenOnlyEntries = counts.entries
            .filter { (_, c) -> c.visible == 0 && c.total > 0 }
        if (hiddenOnlyEntries.isNotEmpty()) {
            val details = hiddenOnlyEntries.take(3).joinToString(" and ") { (kw, c) ->
                "\"$kw\" (${c.total} hidden)"
            }
            append(" $details are inside collapsed/hidden sections. Use scroll_to_text to navigate near them, then click to expand the containing accordion, tab, or toggle.")
        }

        val allZero = counts.values.all { it.total == 0 } && stemmedMatches.isEmpty()
        if (allZero) {
            val searchedForPrice = keywords.any { it.lowercase() in priceKeywords }
            if (searchedForPrice) {
                append(" No matches. Try currency symbols (\"$\", \"HK$\", \"£\") or specific amounts.")
            } else {
                append(" No matches. Try different keywords or synonyms.")
            }
        }
    }

    // --- Result building ---

    private fun buildFinalResult(state: NavigationLoopState): AgenticPageSearchResult {
        val allObservations = collectAllFindings(state.trackedQuestions, state.generalFindings)
        logger.info(
            "Agentic search exhausted {} iterations for {} without finding answer ({} observations, {} captures)",
            MAX_ITERATIONS, state.url, allObservations.size, state.capturedImages.size
        )

        if (allObservations.isNotEmpty()) {
            val synthesized = allObservations.joinToString("; ")
            logger.info("Synthesizing partial answer from {} observations for {}", allObservations.size, state.url)
            return AgenticPageSearchResult(
                answer = synthesized,
                evidence = allObservations.lastOrNull(),
                contentDate = null,
                actionsPerformed = state.actionsPerformed,
                observations = allObservations,
                success = true,
                totalTokenUsage = state.aggregatedTokenUsage,
                discoveredUrls = state.discoveredUrls,
                capturedImages = state.capturedImages.toList()
            )
        }

        return AgenticPageSearchResult(
            answer = null,
            evidence = null,
            contentDate = null,
            actionsPerformed = state.actionsPerformed,
            observations = allObservations,
            success = false,
            totalTokenUsage = state.aggregatedTokenUsage,
            discoveredUrls = state.discoveredUrls,
            capturedImages = state.capturedImages.toList()
        )
    }

    private fun collectAllFindings(
        questions: List<TrackedQuestion>,
        general: List<String>
    ): List<String> = buildList {
        addAll(general)
        questions.flatMapTo(this) { it.findings }
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

    // --- Image processing ---

    private fun cropCaptureRegions(
        screenshotBytes: ByteArray,
        regions: List<CaptureRegion>,
        capturedImages: MutableList<CapturedImage>,
        capturedHashes: MutableSet<String>
    ) {
        val (imgWidth, imgHeight) = imageProcessingService.getImageDimensions(screenshotBytes)
        val sha256 = MessageDigest.getInstance("SHA-256")

        for (region in regions) {
            val x = ((region.x1 / 1000.0) * imgWidth).toInt().coerceIn(0, imgWidth - 1)
            val y = ((region.y1 / 1000.0) * imgHeight).toInt().coerceIn(0, imgHeight - 1)
            val x2 = ((region.x2 / 1000.0) * imgWidth).toInt().coerceIn((x + 1).coerceAtMost(imgWidth), imgWidth)
            val y2 = ((region.y2 / 1000.0) * imgHeight).toInt().coerceIn((y + 1).coerceAtMost(imgHeight), imgHeight)
            val w = x2 - x
            val h = y2 - y

            if (w < MIN_CAPTURE_SIZE_PX || h < MIN_CAPTURE_SIZE_PX) {
                logger.debug("Skipping tiny capture region {}x{} for: {}", w, h, region.relevance)
                continue
            }

            val bytes = imageProcessingService.cropToPng(screenshotBytes, x, y, w, h)

            val hash = sha256.digest(bytes)
            val hashHex = hash.joinToString("") { "%02x".format(it) }
            if (hashHex in capturedHashes) {
                logger.debug("Skipping duplicate capture for: {}", region.relevance)
                continue
            }
            capturedHashes.add(hashHex)

            capturedImages.add(CapturedImage(bytes, "image/png", region.relevance, hash))
            logger.info("Captured visual region ({}x{}): {}", w, h, region.relevance.take(80))
        }
    }

    private fun downscaleScreenshot(imageBytes: ByteArray, maxHeight: Int): ByteArray {
        return imageProcessingService.downscaleToJpeg(
            imageBytes,
            maxHeight,
            (PEEK_JPEG_QUALITY * 100).toInt()
        )
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

    private fun formatCountsDescription(counts: Map<String, IBrowserPage.TextMatchCounts>): String =
        counts.entries.joinToString(", ") { (kw, c) ->
            val hidden = c.total - c.visible
            val base = if (hidden > 0) "$kw: ${c.visible} ($hidden hidden)" else "$kw: ${c.visible}"
            val ctx = c.firstContext
            if (ctx != null && c.total > 0) "$base (e.g. '$ctx')" else base
        }

    private fun updateLastActionOutcome(state: NavigationLoopState, outcome: String) {
        state.actionsPerformed[state.actionsPerformed.lastIndex] =
            state.actionsPerformed.last().copy(outcome = outcome)
    }

    private fun hasConsecutiveClicks(actions: List<NavigationAction>): Boolean =
        actions.zipWithNext().any { (a, b) ->
            a is NavigationAction.Click && b is NavigationAction.Click
        }
}
