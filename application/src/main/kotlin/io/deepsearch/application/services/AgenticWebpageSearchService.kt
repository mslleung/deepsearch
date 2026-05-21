package io.deepsearch.application.services

import io.deepsearch.domain.agents.*
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.agents.infra.TableMarkdownUtils
import io.deepsearch.domain.services.AnnotatedElement
import io.deepsearch.domain.services.AnnotationTarget
import io.deepsearch.domain.services.IDomDiffService
import io.deepsearch.domain.services.IImageProcessingService
import kotlinx.coroutines.*
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import com.ibm.icu.text.Transliterator
import java.security.MessageDigest
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
    private val fullPageNavigationAgent: IFullPageNavigationAgent,
    private val contentExtractionAgent: IContentExtractionAgent,
    private val imageProcessingService: IImageProcessingService,
    private val domDiffService: IDomDiffService,
    private val tokenUsageService: ILlmTokenUsageService,
    private val dispatcherProvider: IDispatcherProvider,
    private val agenticTableConversionAgent: IAgenticTableConversionAgent
) : IAgenticWebpageSearchService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val MAX_ITERATIONS = 12
        private const val MAX_FAILED_CLICKS = 2
        private const val POST_CLICK_DELAY_MS = 150L
        private const val POST_SCROLL_DELAY_MS = 50L
        private const val POST_TYPE_DELAY_MS = 500L
        private const val JPEG_QUALITY = 1.0f
        private const val MIN_CAPTURE_SIZE_PX = 40
        private const val COOKIE_DISMISS_DELAY_MS = 150L

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
        var trackedQuestions: List<TrackedQuestion> = emptyList(),
        val discoveredUrls: MutableList<String> = mutableListOf(),
        val capturedImages: MutableList<CapturedImage> = mutableListOf(),
        val capturedHashes: MutableSet<String> = mutableSetOf(),
        var aggregatedTokenUsage: TokenUsageMetrics = TokenUsageMetrics.empty("gemini-3.1-flash-lite"),
        var previousClickScreenshot: ByteArray? = null,
        var previousDomSnapshot: IBrowserPage.DomSnapshot? = null,
        var lastClickImageCoords: Pair<Int, Int>? = null,
        var lastClickVisualChanged: Boolean = false,
        val failedClickDescs: MutableMap<String, Int> = mutableMapOf(),
        val offPageClickedDescs: MutableSet<String> = mutableSetOf(),
        var pageState: List<String> = emptyList(),
        var isOverlayMode: Boolean = false,
        val extractedRegionContent: MutableList<ExtractedContent> = mutableListOf(),
        val extractedTextHashes: MutableSet<String> = mutableSetOf()
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

        dismissCookieBanner(page, url)

        val actualViewportHeight = page.captureDomSnapshot().viewportHeight

        val state = NavigationLoopState(
            url = url,
            query = query,
            sessionId = sessionId,
            onLinkDiscovered = onLinkDiscovered
        )
        val clock = TimeSource.Monotonic
        var cachedScreenshot: IBrowserPage.Screenshot? = null
        for (iteration in 1..MAX_ITERATIONS) {
            val iterStart = clock.markNow()
            currentCoroutineContext().ensureActive()
            logger.debug("Full-page search iteration {}/{} for {}", iteration, MAX_ITERATIONS, url)

            val isOverlayMode = state.isOverlayMode
            if (isOverlayMode) {
                logger.debug("Fixed overlay detected — using viewport screenshot for iteration {}", iteration)
            }

            if (!isOverlayMode) {
                page.scrollToPercentage(0)
            }

            val fetchStart = clock.markNow()
            val fetchResult = page.fetchAgenticIterationData(isOverlayMode, cachedScreenshot)
            cachedScreenshot = null
            val fetchMs = (clock.markNow() - fetchStart).inWholeMilliseconds
            val scrollableContainers = fetchResult.scrollableContainers
            val screenshot = fetchResult.screenshot
            val title = fetchResult.title
            val interactiveElements = fetchResult.interactiveElements

            if (scrollableContainers.isNotEmpty()) {
                logger.debug("Found {} scrollable containers: {}", scrollableContainers.size, scrollableContainers)
            }

            val annotateStart = clock.markNow()
            val (jpegBytes, imgWidth, imgHeight) = withContext(dispatcherProvider.default) {
                val jpeg = imageProcessingService.downscaleToJpeg(
                    screenshot.bytes, Int.MAX_VALUE, (JPEG_QUALITY * 100).toInt()
                )
                val (w, h) = imageProcessingService.getImageDimensions(jpeg)
                Triple(jpeg, w, h)
            }
            val (annotatedScreenshot, _, elementIndex) = annotateScreenshot(
                IBrowserPage.Screenshot(bytes = jpegBytes, mimeType = ImageMimeType.JPEG),
                interactiveElements
            )
            val screenshotForAgent = annotatedScreenshot
                ?: IBrowserPage.Screenshot(bytes = jpegBytes, mimeType = ImageMimeType.JPEG)

            val currentDomSnapshot = fetchResult.domSnapshot
            if (state.previousClickScreenshot != null) {
                applyVisualDiffAfterClick(jpegBytes, state, currentDomSnapshot)
            }
            state.previousDomSnapshot = currentDomSnapshot

            val finalScreenshot = if (state.lastClickVisualChanged && state.lastClickImageCoords != null) {
                val (cx, cy) = state.lastClickImageCoords!!
                val highlighted = imageProcessingService.highlightRegion(screenshotForAgent.bytes, cx, cy)
                state.lastClickVisualChanged = false
                logger.debug("Applied visual highlight around click ({},{}) on screenshot", cx, cy)
                IBrowserPage.Screenshot(bytes = highlighted, mimeType = ImageMimeType.JPEG)
            } else {
                screenshotForAgent
            }
            val annotateMs = (clock.markNow() - annotateStart).inWholeMilliseconds

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

            // ── Phase 1: Navigation + Content Extraction (parallel) ─────
            val navInput = FullPageNavigationInput(
                fullPageScreenshot = finalScreenshot,
                query = query,
                previousActions = state.actionsPerformed.toList(),
                questions = state.trackedQuestions,
                pageUrl = url,
                pageTitle = title,
                currentIteration = iteration,
                maxIterations = MAX_ITERATIONS,
                pageState = state.pageState,
                isOverlayMode = isOverlayMode,
                scrollStateHint = scrollStateHint,
                extractedRegionContent = state.extractedRegionContent.toList()
            )

            val extractionInput = ContentExtractionInput(
                cleanScreenshot = IBrowserPage.Screenshot(bytes = jpegBytes, mimeType = ImageMimeType.JPEG),
                query = query,
                extractedRegionContent = state.extractedRegionContent.toList(),
                currentIteration = iteration
            )

            val navLlmStart = clock.markNow()
            val (navOutput, extractionOutput) = coroutineScope {
                val navDeferred = async { fullPageNavigationAgent.generate(navInput) }
                val extDeferred = async { contentExtractionAgent.generate(extractionInput) }
                navDeferred.await() to extDeferred.await()
            }
            val navLlmMs = (clock.markNow() - navLlmStart).inWholeMilliseconds

            state.aggregatedTokenUsage = state.aggregatedTokenUsage + navOutput.tokenUsage + extractionOutput.tokenUsage

            tokenUsageService.recordTokenUsage(
                sessionId = sessionId,
                agentName = "FullPageNavigationAgent",
                modelName = navOutput.tokenUsage.modelName,
                promptTokens = navOutput.tokenUsage.promptTokens,
                outputTokens = navOutput.tokenUsage.outputTokens,
                totalTokens = navOutput.tokenUsage.totalTokens
            )
            tokenUsageService.recordTokenUsage(
                sessionId = sessionId,
                agentName = "ContentExtractionAgent",
                modelName = extractionOutput.tokenUsage.modelName,
                promptTokens = extractionOutput.tokenUsage.promptTokens,
                outputTokens = extractionOutput.tokenUsage.outputTokens,
                totalTokens = extractionOutput.tokenUsage.totalTokens
            )

            val postNavStart = clock.markNow()
            state.pageState = navOutput.pageState
            state.trackedQuestions = navOutput.questions

            var pageChanged = false

            val captureRegions = extractionOutput.captureRegions
            if (captureRegions.isNotEmpty()) {
                pageChanged = true
                val (cropResult, newContent) = coroutineScope {
                    val cropDeferred = async(dispatcherProvider.default) {
                        cropCaptureRegions(
                            jpegBytes, captureRegions, state.capturedHashes,
                            imgWidth, imgHeight
                        )
                    }
                    val extractDeferred = async {
                        extractRegionContent(
                            page, captureRegions, imgWidth, imgHeight,
                            isOverlayMode, actualViewportHeight, jpegBytes,
                            state.extractedTextHashes
                        )
                    }
                    cropDeferred.await() to extractDeferred.await()
                }
                state.capturedImages.addAll(cropResult.images)
                state.capturedHashes.addAll(cropResult.newHashes)
                state.extractedRegionContent.addAll(newContent)
            }

            if (navOutput.decision == "exploration_finished") {
                val postNavMs = (clock.markNow() - postNavStart).inWholeMilliseconds
                val iterMs = (clock.markNow() - iterStart).inWholeMilliseconds
                logger.info(
                    "TIMING iter={} total={}ms | fetch={}ms annotate={}ms navLlm={}ms post={}ms",
                    iteration, iterMs, fetchMs, annotateMs, navLlmMs, postNavMs
                )

                if (navOutput.relevantInfoFound == false) {
                    state.actionsPerformed.add(
                        ActionWithOutcome(NavigationAction.GiveUp, observation = navOutput.observation)
                    )
                    logger.info(
                        "Agent finished with relevantInfoFound=false after {} iterations for {}: no relevant information found",
                        iteration, state.url
                    )
                    return AgenticPageSearchResult(
                        answer = null,
                        evidence = null,
                        contentDate = null,
                        actionsPerformed = state.actionsPerformed,
                        observations = emptyList(),
                        success = false,
                        totalTokenUsage = state.aggregatedTokenUsage,
                        discoveredUrls = state.discoveredUrls,
                        capturedImages = state.capturedImages.toList()
                    )
                }

                return handleExplorationFinished(state, iteration)
            }

            // ── Execute proposed actions from Phase 1 ────────────────────────
            for ((actionIdx, action) in navOutput.actions.withIndex()) {
                state.actionsPerformed.add(
                    if (actionIdx == 0) ActionWithOutcome(
                        action,
                        observation = navOutput.observation
                    )
                    else ActionWithOutcome(action)
                )

                val effect = when (action) {
                    is NavigationAction.Click ->
                        if (isOverlayMode)
                            handleViewportClick(
                                action,
                                page,
                                state,
                                jpegBytes,
                                imgWidth,
                                imgHeight,
                                elementIndex
                            )
                        else
                            handleFullPageClick(
                                action,
                                page,
                                state,
                                jpegBytes,
                                imgWidth,
                                imgHeight,
                                elementIndex,
                                actualViewportHeight
                            )

                    is NavigationAction.Type ->
                        if (isOverlayMode)
                            handleViewportType(action, page, state, imgWidth, imgHeight)
                        else
                            handleFullPageType(
                                action,
                                page,
                                state,
                                jpegBytes,
                                imgWidth,
                                imgHeight,
                                actualViewportHeight
                            )

                    is NavigationAction.ScrollAt ->
                        handleScrollAt(action, page, state, imgWidth, imgHeight)

                    else -> {
                        logger.warn("Unsupported action in full-page mode: {}", action)
                        ActionEffect.PAGE_UNCHANGED
                    }
                }
                if (effect == ActionEffect.PAGE_CHANGED) {
                    pageChanged = true
                    val wasOverlay = state.isOverlayMode
                    state.isOverlayMode = try {
                        page.hasModalOverlay()
                    } catch (_: Exception) {
                        false
                    }
                    if (state.isOverlayMode && !wasOverlay) {
                        logger.info("Modal overlay detected after action — switching to viewport mode")
                    } else if (!state.isOverlayMode && wasOverlay) {
                        logger.info("Overlay dismissed after action — returning to full-page mode")
                    }
                    break
                }
            }
            val postNavMs = (clock.markNow() - postNavStart).inWholeMilliseconds
            val iterMs = (clock.markNow() - iterStart).inWholeMilliseconds
            logger.info(
                "TIMING iter={} total={}ms | fetch={}ms annotate={}ms navLlm={}ms post={}ms",
                iteration, iterMs, fetchMs, annotateMs, navLlmMs, postNavMs
            )
            if (!pageChanged) {
                cachedScreenshot = screenshot
            }
        }

        return buildFinalResult(state)
    }

    private suspend fun handleFullPageClick(
        action: NavigationAction.Click,
        page: IBrowserPage,
        state: NavigationLoopState,
        rawScreenshotBytes: ByteArray,
        imgWidth: Int,
        imgHeight: Int,
        elementIndex: Map<Int, AnnotatedElement> = emptyMap(),
        viewportHeight: Int = 1080
    ): ActionEffect {
        val desc = (action.label ?: action.reason).take(60)

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

        val coords = resolveFullPageClickWithLabels(action, page, imgWidth, imgHeight, elementIndex, viewportHeight)
        if (coords == null) {
            logger.warn("Could not resolve full-page click coordinates for: {}", desc)
            updateLastActionOutcome(state, "Could not determine click coordinates for '$desc'.")
            return ActionEffect.PAGE_UNCHANGED
        }
        val (viewportX, viewportY) = coords

        if (action.elementLabel != null) {
            val resolvedElement = elementIndex[action.elementLabel]
            logger.debug(
                "Full-page click element#{} '{}' -> viewport ({},{}) (reason: {}), resolvedElement=<{}> text='{}'",
                action.elementLabel, desc, viewportX, viewportY, action.reason,
                resolvedElement?.tag ?: "?", resolvedElement?.text?.take(60) ?: "?"
            )
        } else {
            logger.debug(
                "Full-page click box_2d={} center=({},{}) -> viewport ({},{}) - {} (reason: {})",
                action.box2d, action.centerX, action.centerY, viewportX, viewportY, desc, action.reason
            )
        }

        state.previousClickScreenshot = rawScreenshotBytes
        val element = action.elementLabel?.let { elementIndex[it] }
        state.lastClickImageCoords = if (element != null) {
            element.centerX to element.centerY
        } else if (action.centerX != null && action.centerY != null) {
            val px = ((action.centerX!! / 1000.0) * imgWidth).toInt()
            val py = ((action.centerY!! / 1000.0) * imgHeight).toInt()
            px to py
        } else null

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
            state.previousClickScreenshot = null
            state.lastClickImageCoords = null
            updateLastActionOutcome(state, buildOffPageOutcome(targetUrl))
            return ActionEffect.PAGE_UNCHANGED
        }

        return ActionEffect.PAGE_CHANGED
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

        val actualScrollPercent = page.getScrollPosition()
        val actualScrollY = (actualScrollPercent / 100.0 * scrollableRange).toInt()
        val viewportX = pageX
        val viewportY = pageY - actualScrollY

        val desc = action.reason.ifEmpty { "input at (${action.x},${action.y})" }

        logger.debug(
            "Full-page typing '{}' at normalized ({},{}) -> viewport ({},{}) - {} (reason: {})",
            action.text, action.x, action.y, viewportX, viewportY, desc, action.reason
        )

        state.previousClickScreenshot = rawScreenshotBytes
        state.lastClickImageCoords = pageX to pageY

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
        rawScreenshotBytes: ByteArray,
        imgWidth: Int,
        imgHeight: Int,
        elementIndex: Map<Int, AnnotatedElement> = emptyMap()
    ): ActionEffect {
        val desc = (action.label ?: action.reason)

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

        val viewportX: Int
        val viewportY: Int
        if (action.elementLabel != null) {
            val element = elementIndex[action.elementLabel]
            if (element != null) {
                viewportX = element.centerX
                viewportY = element.centerY
            } else {
                logger.warn("element_label={} not in viewport index, falling back to box_2d", action.elementLabel)
                val cx = action.centerX ?: run {
                    updateLastActionOutcome(state, "Could not determine click coordinates for '$desc'.")
                    return ActionEffect.PAGE_UNCHANGED
                }
                viewportX = toViewportCoord(cx, imgWidth)
                viewportY = toViewportCoord(action.centerY!!, imgHeight)
            }
        } else {
            val cx = action.centerX ?: run {
                logger.warn("Viewport click has no coordinates for: {}", desc)
                updateLastActionOutcome(state, "Could not determine click coordinates for '$desc'.")
                return ActionEffect.PAGE_UNCHANGED
            }
            viewportX = toViewportCoord(cx, imgWidth)
            viewportY = toViewportCoord(action.centerY!!, imgHeight)
        }

        logger.debug(
            "Viewport click element_label={} box_2d={} -> viewport ({},{}) - {} (reason: {})",
            action.elementLabel, action.box2d, viewportX, viewportY, desc, action.reason
        )

        state.previousClickScreenshot = rawScreenshotBytes
        state.lastClickImageCoords = viewportX to viewportY

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
            state.previousClickScreenshot = null
            state.lastClickImageCoords = null
            updateLastActionOutcome(state, buildOffPageOutcome(targetUrl))
            return ActionEffect.PAGE_UNCHANGED
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
            action.text.take(30), action.x, action.y, viewportX, viewportY, desc, action.reason
        )

        page.clickAtCoordinates(viewportX, viewportY)
        delay(POST_CLICK_DELAY_MS)
        page.typeText(action.text)
        delay(POST_TYPE_DELAY_MS)
        return ActionEffect.PAGE_CHANGED
    }

    private suspend fun resolveFullPageClick(
        action: NavigationAction.Click,
        page: IBrowserPage,
        fullPageImgWidth: Int,
        fullPageImgHeight: Int,
        viewportHeight: Int = 1080
    ): Pair<Int, Int>? {
        val cx = action.centerX ?: return null
        val cy = action.centerY ?: return null
        val pageX = ((cx / 1000.0) * fullPageImgWidth).toInt()
        val pageY = ((cy / 1000.0) * fullPageImgHeight).toInt()
        val targetScrollY = (pageY - viewportHeight / 2).coerceAtLeast(0)
        val scrollableRange = (fullPageImgHeight - viewportHeight).coerceAtLeast(1)
        val percent = (targetScrollY * 100 / scrollableRange).coerceIn(0, 100)
        page.scrollToPercentage(percent)
        delay(POST_SCROLL_DELAY_MS)

        val actualScrollPercent = page.getScrollPosition()
        val actualScrollY = (actualScrollPercent / 100.0 * scrollableRange).toInt()
        return pageX to (pageY - actualScrollY)
    }

    private suspend fun resolveFullPageClickWithLabels(
        action: NavigationAction.Click,
        page: IBrowserPage,
        fullPageImgWidth: Int,
        fullPageImgHeight: Int,
        elementIndex: Map<Int, AnnotatedElement>,
        viewportHeight: Int = 1080
    ): Pair<Int, Int>? {
        val label = action.elementLabel
        if (label != null) {
            val element = elementIndex[label]
            if (element != null) {
                val pageX = element.centerX
                val pageY = element.centerY
                val targetScrollY = (pageY - viewportHeight / 2).coerceAtLeast(0)
                val scrollableRange = (fullPageImgHeight - viewportHeight).coerceAtLeast(1)
                val percent = (targetScrollY * 100 / scrollableRange).coerceIn(0, 100)
                page.scrollToPercentage(percent)
                delay(POST_SCROLL_DELAY_MS)

                val actualScrollPercent = page.getScrollPosition()
                val actualScrollY = (actualScrollPercent / 100.0 * scrollableRange).toInt()
                return pageX to (pageY - actualScrollY)
            }
            logger.warn(
                "element_label={} not found in index (size={}), falling back to box_2d",
                label,
                elementIndex.size
            )
        }
        return resolveFullPageClick(action, page, fullPageImgWidth, fullPageImgHeight, viewportHeight)
    }

    private fun annotateScreenshot(
        rawScreenshot: IBrowserPage.Screenshot,
        elements: List<IBrowserPage.InteractiveElementInfo>
    ): Triple<IBrowserPage.Screenshot?, String?, Map<Int, AnnotatedElement>> {
        if (elements.isEmpty()) return Triple(null, null, emptyMap())

        val targets = elements.map { e ->
            AnnotationTarget(
                tag = e.tag, text = e.text, role = e.role, ariaLabel = e.ariaLabel,
                left = e.boundingBox.left, top = e.boundingBox.top,
                right = e.boundingBox.right, bottom = e.boundingBox.bottom,
                centerX = e.centerX, centerY = e.centerY, index = e.index
            )
        }

        val annotated = imageProcessingService.annotate(rawScreenshot.bytes, targets)

        val labeledText = buildString {
            for (e in elements) {
                val desc = e.ariaLabel ?: e.text.take(60).ifBlank { e.tag }
                val roleTag = e.role?.let { " [$it]" } ?: ""
                appendLine("  [${e.index}] <${e.tag}>$roleTag $desc")
            }
        }

        logger.debug("Annotated screenshot with {} labeled elements", elements.size)

        return Triple(
            IBrowserPage.Screenshot(bytes = annotated.imageBytes, mimeType = ImageMimeType.JPEG),
            labeledText,
            annotated.elementIndex
        )
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
            is NavigationAction.Click -> (lastAction.label ?: lastAction.reason).take(60)

            is NavigationAction.Type -> lastAction.reason.take(60)
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

    private fun handleExplorationFinished(
        state: NavigationLoopState,
        iteration: Int
    ): AgenticPageSearchResult {
        val answer = state.extractedRegionContent
            .joinToString("\n\n") { "[${it.description}]${if (it.isTable) " (table)" else ""}:\n${it.text}" }
            .ifBlank { null }
        val observations = state.extractedRegionContent.map { "[${it.description}] ${it.text}" }

        if (answer != null) {
            logger.info(
                "Agentic search finished after {} iterations ({} extracted, {} captures) for {}: {}",
                iteration, state.extractedRegionContent.size, state.capturedImages.size, state.url, answer.take(100)
            )
        } else {
            logger.info(
                "Agentic search finished with no extracted knowledge after {} iterations for {}",
                iteration, state.url
            )
        }

        return AgenticPageSearchResult(
            answer = answer,
            evidence = observations.lastOrNull(),
            contentDate = null,
            actionsPerformed = state.actionsPerformed,
            observations = observations,
            success = answer != null,
            totalTokenUsage = state.aggregatedTokenUsage,
            discoveredUrls = state.discoveredUrls,
            capturedImages = state.capturedImages.toList()
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

    private fun buildFinalResult(state: NavigationLoopState): AgenticPageSearchResult {
        val observations = state.extractedRegionContent.map { "[${it.description}] ${it.text}" }
        logger.info(
            "Agentic search exhausted {} iterations for {} without finding answer ({} extracted, {} captures)",
            MAX_ITERATIONS, state.url, state.extractedRegionContent.size, state.capturedImages.size
        )

        if (state.extractedRegionContent.isNotEmpty()) {
            val synthesized = observations.joinToString("; ")
            logger.info("Synthesizing partial answer from {} extracted regions for {}", observations.size, state.url)
            return AgenticPageSearchResult(
                answer = synthesized,
                evidence = observations.lastOrNull(),
                contentDate = null,
                actionsPerformed = state.actionsPerformed,
                observations = observations,
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
            observations = emptyList(),
            success = false,
            totalTokenUsage = state.aggregatedTokenUsage,
            discoveredUrls = state.discoveredUrls,
            capturedImages = state.capturedImages.toList()
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

    // --- Image processing ---

    private data class CropResult(
        val images: List<CapturedImage>,
        val newHashes: Set<String>
    )

    private fun cropCaptureRegions(
        screenshotBytes: ByteArray,
        regions: List<CaptureRegion>,
        existingHashes: Set<String>,
        imgWidth: Int,
        imgHeight: Int
    ): CropResult {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val images = mutableListOf<CapturedImage>()
        val newHashes = mutableSetOf<String>()

        for (region in regions) {
            val boxes = when (region) {
                is CaptureRegion.Element -> listOf(region.boundingBox)
                is CaptureRegion.Table -> region.regions.map { it.boundingBox }
            }

            for (box in boxes) {
                val x = ((box.x1 / 1000.0) * imgWidth).toInt().coerceIn(0, imgWidth - 1)
                val y = ((box.y1 / 1000.0) * imgHeight).toInt().coerceIn(0, imgHeight - 1)
                val x2 = ((box.x2 / 1000.0) * imgWidth).toInt().coerceIn((x + 1).coerceAtMost(imgWidth), imgWidth)
                val y2 = ((box.y2 / 1000.0) * imgHeight).toInt().coerceIn((y + 1).coerceAtMost(imgHeight), imgHeight)
                val w = x2 - x
                val h = y2 - y

                if (w < MIN_CAPTURE_SIZE_PX || h < MIN_CAPTURE_SIZE_PX) {
                    logger.debug("Skipping tiny capture region {}x{} for: {}", w, h, region.relevance)
                    continue
                }

                val bytes = imageProcessingService.cropToPng(screenshotBytes, x, y, w, h)

                val hash = sha256.digest(bytes)
                val hashHex = hash.joinToString("") { "%02x".format(it) }
                if (hashHex in existingHashes || hashHex in newHashes) {
                    logger.debug("Skipping duplicate capture for: {}", region.relevance)
                    continue
                }
                newHashes.add(hashHex)

                images.add(CapturedImage(bytes, "image/png", region.relevance, hash))
                logger.info("Captured visual region ({}x{}): {}", w, h, region.relevance.take(80))
            }
        }
        return CropResult(images, newHashes)
    }

    private suspend fun extractRegionContent(
        page: IBrowserPage,
        regions: List<CaptureRegion>,
        imgWidth: Int,
        imgHeight: Int,
        isOverlayMode: Boolean,
        viewportHeight: Int,
        jpegBytes: ByteArray,
        extractedTextHashes: MutableSet<String>
    ): List<ExtractedContent> {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val scrollableRange = (imgHeight - viewportHeight).coerceAtLeast(1)
        val results = mutableListOf<ExtractedContent>()
        for (region in regions) {
            when (region) {
                is CaptureRegion.Element -> {
                    val box = region.boundingBox
                    val pageX1 = ((box.x1 / 1000.0) * imgWidth).toInt()
                    val pageY1 = ((box.y1 / 1000.0) * imgHeight).toInt()
                    val pageX2 = ((box.x2 / 1000.0) * imgWidth).toInt()
                    val pageY2 = ((box.y2 / 1000.0) * imgHeight).toInt()

                    val (vpX1, vpY1, vpX2, vpY2) = toViewportRegion(
                        pageX1, pageY1, pageX2, pageY2,
                        isOverlayMode, viewportHeight, scrollableRange, page
                    )

                    val content = page.extractContentInRegion(
                        vpX1.coerceAtLeast(0), vpY1.coerceAtLeast(0),
                        vpX2.coerceAtLeast(vpX1 + 1), vpY2.coerceAtLeast(vpY1 + 1)
                    )

                    if (content.text.isNotBlank()) {
                        val normalizedText = normalizeText(content.text)
                        val textHash = sha256.digest(normalizedText.trim().replace("\\s+".toRegex(), " ").toByteArray())
                            .joinToString("") { "%02x".format(it) }
                        if (textHash in extractedTextHashes) {
                            logger.debug("Skipping duplicate extracted text for: {}", region.relevance.take(80))
                            continue
                        }
                        extractedTextHashes.add(textHash)
                        results.add(ExtractedContent(description = region.relevance, text = normalizedText, isTable = false))
                        logger.info("Extracted element text ({} chars): {}", content.text.length, region.relevance.take(80))
                    }
                }

                is CaptureRegion.Table -> {
                    val subContents = region.regions.map { sub ->
                        val box = sub.boundingBox
                        val pageX1 = ((box.x1 / 1000.0) * imgWidth).toInt()
                        val pageY1 = ((box.y1 / 1000.0) * imgHeight).toInt()
                        val pageX2 = ((box.x2 / 1000.0) * imgWidth).toInt()
                        val pageY2 = ((box.y2 / 1000.0) * imgHeight).toInt()

                        val (vpX1, vpY1, vpX2, vpY2) = toViewportRegion(
                            pageX1, pageY1, pageX2, pageY2,
                            isOverlayMode, viewportHeight, scrollableRange, page
                        )

                        val content = page.extractContentInRegion(
                            vpX1.coerceAtLeast(0), vpY1.coerceAtLeast(0),
                            vpX2.coerceAtLeast(vpX1 + 1), vpY2.coerceAtLeast(vpY1 + 1)
                        )
                        Triple(sub, content, content.html)
                    }

                    val subRegionImages = region.regions.mapNotNull { sub ->
                        val box = sub.boundingBox
                        val x = ((box.x1 / 1000.0) * imgWidth).toInt().coerceIn(0, imgWidth - 1)
                        val y = ((box.y1 / 1000.0) * imgHeight).toInt().coerceIn(0, imgHeight - 1)
                        val x2 = ((box.x2 / 1000.0) * imgWidth).toInt().coerceIn((x + 1).coerceAtMost(imgWidth), imgWidth)
                        val y2 = ((box.y2 / 1000.0) * imgHeight).toInt().coerceIn((y + 1).coerceAtMost(imgHeight), imgHeight)
                        val w = x2 - x
                        val h = y2 - y
                        if (w < MIN_CAPTURE_SIZE_PX || h < MIN_CAPTURE_SIZE_PX) return@mapNotNull null
                        val cropped = imageProcessingService.cropToPng(jpegBytes, x, y, w, h)
                        TableSubRegionImage(
                            bytes = cropped,
                            mimeType = ImageMimeType.PNG,
                            role = sub.role,
                            description = sub.description
                        )
                    }
                    if (subRegionImages.isEmpty()) continue

                    val combinedHtml = subContents
                        .filter { (_, _, html) -> html.isNotBlank() }
                        .joinToString("\n") { (sub, _, html) ->
                            "<!-- ${sub.role.name}: ${sub.description} -->\n$html"
                        }

                    val input = AgenticTableConversionInput(
                        subRegionImages = subRegionImages,
                        cleanedHtml = combinedHtml.ifBlank { null },
                        auxiliaryInfo = region.relevance
                    )
                    val output = agenticTableConversionAgent.generate(input)

                    if (output.htmlTable.isNotBlank()) {
                        val markdown = TableMarkdownUtils.transformHTMLTablesToMarkdown(output.htmlTable).ifBlank { null }
                        if (markdown != null) {
                            val normalizedMarkdown = normalizeText(markdown)
                            val textHash = sha256.digest(normalizedMarkdown.trim().replace("\\s+".toRegex(), " ").toByteArray())
                                .joinToString("") { "%02x".format(it) }
                            if (textHash in extractedTextHashes) {
                                logger.debug("Skipping duplicate extracted table for: {}", region.relevance.take(80))
                                continue
                            }
                            extractedTextHashes.add(textHash)
                            results.add(ExtractedContent(description = region.relevance, text = normalizedMarkdown, isTable = true))
                            logger.info("Extracted table ({} chars, {} sub-regions): {}", markdown.length, region.regions.size, region.relevance.take(80))
                            continue
                        }
                    }

                    val fallbackText = subContents
                        .filter { (_, content, _) -> content.text.isNotBlank() }
                        .joinToString("\n") { (sub, content, _) ->
                            "[${sub.role.name}: ${sub.description}]\n${content.text}"
                        }
                    if (fallbackText.isNotBlank()) {
                        val normalizedText = normalizeText(fallbackText)
                        val textHash = sha256.digest(normalizedText.trim().replace("\\s+".toRegex(), " ").toByteArray())
                            .joinToString("") { "%02x".format(it) }
                        if (textHash in extractedTextHashes) {
                            logger.debug("Skipping duplicate extracted text for: {}", region.relevance.take(80))
                            continue
                        }
                        extractedTextHashes.add(textHash)
                        results.add(ExtractedContent(description = region.relevance, text = normalizedText, isTable = true))
                        logger.info("Table conversion returned blank, using raw sub-region text ({} chars): {}", normalizedText.length, region.relevance.take(80))
                    }
                }
            }
        }
        return results
    }

    private suspend fun toViewportRegion(
        pageX1: Int, pageY1: Int, pageX2: Int, pageY2: Int,
        isOverlayMode: Boolean, viewportHeight: Int, scrollableRange: Int,
        page: IBrowserPage
    ): List<Int> {
        return if (isOverlayMode) {
            listOf(pageX1, pageY1, pageX2, pageY2)
        } else {
            val regionCenterY = (pageY1 + pageY2) / 2
            val targetScrollY = (regionCenterY - viewportHeight / 2).coerceAtLeast(0)
            val percent = (targetScrollY * 100 / scrollableRange).coerceIn(0, 100)
            try {
                page.scrollToPercentage(percent)
                delay(POST_SCROLL_DELAY_MS)
            } catch (_: Exception) { /* best effort */ }
            val actualScrollPercent = try { page.getScrollPosition() } catch (_: Exception) { percent }
            val actualScrollY = (actualScrollPercent / 100.0 * scrollableRange).toInt()
            listOf(pageX1, pageY1 - actualScrollY, pageX2, pageY2 - actualScrollY)
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
