package io.deepsearch.application.services

import io.deepsearch.domain.agents.ActionWithOutcome
import io.deepsearch.domain.agents.CaptureRegion
import io.deepsearch.domain.agents.ExtractedContent
import io.deepsearch.domain.agents.IFullPageNavigationAgent
import io.deepsearch.domain.agents.FullPageNavigationInput
import io.deepsearch.domain.agents.NavigationAction
import io.deepsearch.domain.agents.ScrollDirection
import io.deepsearch.domain.agents.TrackedQuestion
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.services.AnnotatedElement
import io.deepsearch.domain.services.AnnotationTarget
import io.deepsearch.domain.services.IDomDiffService
import io.deepsearch.domain.services.IImageProcessingService
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
    private val fullPageNavigationAgent: IFullPageNavigationAgent,
    private val imageProcessingService: IImageProcessingService,
    private val domDiffService: IDomDiffService,
    private val tokenUsageService: ILlmTokenUsageService
) : IAgenticWebpageSearchService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val MAX_ITERATIONS = 12
        private const val MAX_FAILED_CLICKS = 2
        private const val POST_CLICK_DELAY_MS = 350L
        private const val POST_SCROLL_DELAY_MS = 150L
        private const val POST_TYPE_DELAY_MS = 500L
        private const val JPEG_QUALITY = 1.0f
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
        var previousClickScreenshot: ByteArray? = null,
        var previousDomSnapshot: IBrowserPage.DomSnapshot? = null,
        val failedClickDescs: MutableMap<String, Int> = mutableMapOf(),
        val offPageClickedDescs: MutableSet<String> = mutableSetOf(),
        var pageState: List<String> = emptyList(),
        var isOverlayMode: Boolean = false,
        val extractedRegionContent: MutableList<ExtractedContent> = mutableListOf()
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

        val vpScreenshot = page.takeScreenshot()
        val vpDims = imageProcessingService.getImageDimensions(vpScreenshot.bytes)
        val actualViewportHeight = vpDims.second

        val state = NavigationLoopState(
            url = url,
            query = query,
            sessionId = sessionId,
            onLinkDiscovered = onLinkDiscovered
        )
        for (iteration in 1..MAX_ITERATIONS) {
            currentCoroutineContext().ensureActive()
            logger.debug("Full-page search iteration {}/{} for {}", iteration, MAX_ITERATIONS, url)

            val isOverlayMode = state.isOverlayMode
            if (isOverlayMode) {
                logger.debug("Fixed overlay detected — using viewport screenshot for iteration {}", iteration)
            }

            if (!isOverlayMode) {
                page.scrollToPercentage(0)
            }

            val scrollableContainers = try {
                page.annotateScrollableContainers()
            } catch (_: Exception) {
                emptyList()
            }
            if (scrollableContainers.isNotEmpty()) {
                logger.debug("Found {} scrollable containers: {}", scrollableContainers.size, scrollableContainers)
            }

            val screenshot = if (isOverlayMode) page.takeScreenshot() else page.takeFullPageScreenshot()
            val title = page.getTitle()
            val desc = page.getDescription()
            val interactiveElements = page.getInteractiveElements(fullPage = !isOverlayMode)

            val jpegBytes =
                imageProcessingService.downscaleToJpeg(screenshot.bytes, Int.MAX_VALUE, (JPEG_QUALITY * 100).toInt())

            val (annotatedScreenshot, labeledElements, elementIndex) = annotateScreenshot(
                IBrowserPage.Screenshot(bytes = jpegBytes, mimeType = ImageMimeType.JPEG),
                interactiveElements
            )
            val screenshotForAgent = annotatedScreenshot
                ?: IBrowserPage.Screenshot(bytes = jpegBytes, mimeType = ImageMimeType.JPEG)

            if (state.previousClickScreenshot != null) {
                val currentDomSnapshot = try {
                    page.captureDomSnapshot()
                } catch (_: Exception) {
                    null
                }
                applyVisualDiffAfterClick(jpegBytes, state, currentDomSnapshot)
            }

            state.previousDomSnapshot = try {
                page.captureDomSnapshot()
            } catch (_: Exception) {
                null
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

            val input = FullPageNavigationInput(
                fullPageScreenshot = screenshotForAgent,
                query = query,
                previousActions = state.actionsPerformed.toList(),
                questions = state.trackedQuestions,
                generalFindings = state.generalFindings,
                pageUrl = url,
                pageTitle = title,
                pageDescription = desc,
                currentIteration = iteration,
                maxIterations = MAX_ITERATIONS,
                pageState = state.pageState,
                isOverlayMode = isOverlayMode,
                labeledElements = labeledElements,
                scrollStateHint = scrollStateHint,
                extractedRegionContent = state.extractedRegionContent.toList()
            )

            val output = fullPageNavigationAgent.generate(input)
            state.aggregatedTokenUsage = state.aggregatedTokenUsage + output.tokenUsage

            tokenUsageService.recordTokenUsage(
                sessionId = sessionId,
                agentName = "FullPageNavigationAgent",
                modelName = output.tokenUsage.modelName,
                promptTokens = output.tokenUsage.promptTokens,
                outputTokens = output.tokenUsage.outputTokens,
                totalTokens = output.tokenUsage.totalTokens
            )

            val newFindings = updateFindings(output.questionsState, output.generalFindings, state)
            state.pageState = output.pageState

            val (imgWidth, imgHeight) = imageProcessingService.getImageDimensions(jpegBytes)

            if (output.captureRegions.isNotEmpty()) {
                cropCaptureRegions(
                    jpegBytes,
                    output.captureRegions,
                    state.capturedImages,
                    state.capturedHashes
                )
                extractRegionContent(
                    page, output.captureRegions, imgWidth, imgHeight,
                    isOverlayMode, actualViewportHeight, state.extractedRegionContent
                )
            }

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
                }
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

    private fun updateFindings(
        questionsState: List<TrackedQuestion>,
        generalFindings: List<String>,
        state: NavigationLoopState
    ): List<String> {
        val previousAllFacts = buildSet {
            state.trackedQuestions.flatMapTo(this) { it.findings }
            addAll(state.generalFindings)
        }
        state.trackedQuestions = questionsState
        state.generalFindings = generalFindings
        val currentAllFacts = buildSet {
            questionsState.flatMapTo(this) { it.findings }
            addAll(generalFindings)
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

    private suspend fun extractRegionContent(
        page: IBrowserPage,
        regions: List<CaptureRegion>,
        imgWidth: Int,
        imgHeight: Int,
        isOverlayMode: Boolean,
        viewportHeight: Int,
        extractedContent: MutableList<ExtractedContent>
    ) {
        val scrollableRange = (imgHeight - viewportHeight).coerceAtLeast(1)
        for (region in regions) {
            val pageX1 = ((region.x1 / 1000.0) * imgWidth).toInt()
            val pageY1 = ((region.y1 / 1000.0) * imgHeight).toInt()
            val pageX2 = ((region.x2 / 1000.0) * imgWidth).toInt()
            val pageY2 = ((region.y2 / 1000.0) * imgHeight).toInt()

            val (vpX1, vpY1, vpX2, vpY2) = if (isOverlayMode) {
                listOf(pageX1, pageY1, pageX2, pageY2)
            } else {
                val regionCenterY = (pageY1 + pageY2) / 2
                val targetScrollY = (regionCenterY - viewportHeight / 2).coerceAtLeast(0)
                val percent = (targetScrollY * 100 / scrollableRange).coerceIn(0, 100)
                try {
                    page.scrollToPercentage(percent)
                    delay(POST_SCROLL_DELAY_MS)
                } catch (_: Exception) { /* best effort */
                }
                val actualScrollPercent = try {
                    page.getScrollPosition()
                } catch (_: Exception) {
                    percent
                }
                val actualScrollY = (actualScrollPercent / 100.0 * scrollableRange).toInt()
                listOf(pageX1, pageY1 - actualScrollY, pageX2, pageY2 - actualScrollY)
            }

            val content = page.extractContentInRegion(
                vpX1.coerceAtLeast(0), vpY1.coerceAtLeast(0),
                vpX2.coerceAtLeast(vpX1 + 1), vpY2.coerceAtLeast(vpY1 + 1)
            )
            if (content.text.isNotBlank()) {
                extractedContent.add(
                    ExtractedContent(
                        description = region.relevance,
                        text = content.text,
                        isTable = content.isTable
                    )
                )
                logger.info(
                    "Extracted region text ({} chars, table={}): {}",
                    content.text.length, content.isTable, region.relevance.take(80)
                )
            }
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
