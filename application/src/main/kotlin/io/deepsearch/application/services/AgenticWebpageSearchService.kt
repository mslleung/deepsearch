package io.deepsearch.application.services

import io.deepsearch.domain.agents.ActionWithOutcome
import io.deepsearch.domain.agents.CaptureRegion
import io.deepsearch.domain.agents.ElementLabel
import io.deepsearch.domain.agents.IWebpageNavigationAgent
import io.deepsearch.domain.agents.NavigationAction
import io.deepsearch.domain.agents.ScrollDirection
import io.deepsearch.domain.agents.WebpageNavigationInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.services.ScreenshotAnnotationService
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
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
    private val screenshotAnnotationService: ScreenshotAnnotationService,
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

    override suspend fun searchWithinPage(
        url: String,
        query: String,
        sessionId: SessionId
    ): AgenticPageSearchResult {
        logger.info("Starting agentic page search (own page) for query='{}' on url={}", query, url)

        return browserPool.withPage { page ->
            page.navigate(url)
            page.waitForLoad()
            searchWithinPage(page, url, query, sessionId)
        }
    }

    override suspend fun searchWithinPage(
        page: IBrowserPage,
        url: String,
        query: String,
        sessionId: SessionId,
        onLinkDiscovered: (suspend (String) -> Unit)?
    ): AgenticPageSearchResult {
        logger.info("Starting agentic page search for query='{}' on url={}", query, url)

        dismissCookieBanner(page, url)

        val actionsPerformed = mutableListOf<ActionWithOutcome>()
        val observations = mutableListOf<String>()
        var openQuestions = listOf<String>()
        val answeredQuestions = mutableListOf<String>()
        val discoveredUrls = mutableListOf<String>()
        val capturedImages = mutableListOf<CapturedImage>()
        val capturedHashes = mutableSetOf<String>()
        var aggregatedTokenUsage = TokenUsageMetrics.empty("gemini-3.1-flash-lite")

        // Navigation Loop
        var previousClickScreenshot: ByteArray? = null
        val failedClickDescs = mutableMapOf<String, Int>()
        val offPageClickedDescs = mutableSetOf<String>()
        var lastActionWasClick = false
        var peekScreenshotOverride: IBrowserPage.Screenshot? = null
        var lastFindCounts: Map<String, IBrowserPage.TextMatchCounts> = emptyMap()

        for (iteration in 1..MAX_ITERATIONS) {
            currentCoroutineContext().ensureActive()
            logger.debug("Agentic search iteration {}/{} for {}", iteration, MAX_ITERATIONS, url)

            data class IterationContext(
                val elements: List<IBrowserPage.InteractiveElementInfo>,
                val screenshot: IBrowserPage.Screenshot,
                val pageTitle: String,
                val pageDescription: String?,
                val scrollPercent: Int
            )

            val ctx = coroutineScope {
                val elementsDeferred = async { page.getInteractiveElements() }
                val screenshotDeferred = async { page.takeScreenshot() }
                val titleDeferred = async { page.getTitle() }
                val descDeferred = async { page.getDescription() }
                val scrollDeferred = async { page.getScrollPosition() }
                IterationContext(
                    elements = elementsDeferred.await(),
                    screenshot = screenshotDeferred.await(),
                    pageTitle = titleDeferred.await(),
                    pageDescription = descDeferred.await(),
                    scrollPercent = scrollDeferred.await()
                )
            }
            val interactiveElements = ctx.elements
            val rawScreenshot = ctx.screenshot
            logger.debug("Found {} interactive elements", interactiveElements.size)

            if (previousClickScreenshot != null && lastActionWasClick) {
                val changed = screenshotAnnotationService.hasVisualChange(
                    previousClickScreenshot, rawScreenshot.bytes
                )
                val lastEntry = actionsPerformed.last()
                val lastAction = lastEntry.action
                val clickDesc = when (lastAction) {
                    is NavigationAction.Click -> lastAction.elementDescription ?: ""
                    is NavigationAction.ClickAt -> lastAction.elementDescription ?: "(${lastAction.x},${lastAction.y})"
                    is NavigationAction.Type -> lastAction.elementDescription ?: ""
                    else -> ""
                }
                if (changed) {
                    actionsPerformed[actionsPerformed.lastIndex] = lastEntry.copy(
                        outcome = "VISIBLE CHANGE on the page."
                    )
                    failedClickDescs.remove(clickDesc)
                } else {
                    val failCount = failedClickDescs.merge(clickDesc, 1, Int::plus)!!
                    actionsPerformed[actionsPerformed.lastIndex] = lastEntry.copy(
                        outcome = if (failCount >= MAX_FAILED_CLICKS) {
                            "Element '$clickDesc' has FAILED $failCount times — it is NOT clickable. " +
                                    "STOP trying it. Drop unanswerable questions from openQuestions and " +
                                    "use answer_found with the findings you already have."
                        } else {
                            "NO visible change. Try a different element or approach."
                        }
                    )
                }
                logger.debug("Visual diff after click: changed={}", changed)
            }
            lastActionWasClick = false

            val annotated = screenshotAnnotationService.annotate(
                rawScreenshot.bytes,
                interactiveElements
            )

            val debugDir = java.io.File("/tmp/deepsearch-debug")
            debugDir.mkdirs()
            java.io.File(debugDir, "annotated-iter$iteration.jpg").writeBytes(annotated.imageBytes)
            logger.debug("Saved debug screenshot to /tmp/deepsearch-debug/annotated-iter$iteration.jpg")

            val elementLabels = interactiveElements
                .filter { el ->
                    val elDesc = if (el.text.isNotBlank()) {
                        "${el.tag} '${el.text.take(40)}'".trim()
                    } else ""
                    elDesc !in offPageClickedDescs && failedClickDescs.getOrDefault(elDesc, 0) < MAX_FAILED_CLICKS
                }
                .map { el ->
                    ElementLabel(
                        labelNumber = el.index,
                        tag = el.tag,
                        text = el.text.take(60),
                        role = el.role,
                        states = el.states
                    )
                }

            logger.debug("Element labels: {}", elementLabels.joinToString("\n") { el ->
                "  [${el.labelNumber}] ${el.tag} (${el.role ?: "no-role"}) ${el.states}: ${el.text}"
            })

            val annotatedScreenshot = IBrowserPage.Screenshot(
                bytes = annotated.imageBytes,
                mimeType = ImageMimeType.JPEG
            )

            val screenshotForAgent = peekScreenshotOverride ?: annotatedScreenshot
            peekScreenshotOverride = null

            val input = WebpageNavigationInput(
                screenshot = screenshotForAgent,
                query = query,
                previousActions = actionsPerformed.toList(),
                elementLabels = elementLabels,
                answeredQuestions = answeredQuestions.toList(),
                openQuestions = openQuestions,
                accumulatedFindings = observations.toList(),
                pageUrl = url,
                pageTitle = ctx.pageTitle,
                pageDescription = ctx.pageDescription,
                scrollPercent = ctx.scrollPercent
            )

            val output = webpageNavigationAgent.generate(input)
            aggregatedTokenUsage = aggregatedTokenUsage + output.tokenUsage

            tokenUsageService.recordTokenUsage(
                sessionId = sessionId,
                agentName = "WebpageNavigationAgent",
                modelName = output.tokenUsage.modelName,
                promptTokens = output.tokenUsage.promptTokens,
                outputTokens = output.tokenUsage.outputTokens,
                totalTokens = output.tokenUsage.totalTokens
            )

            val action = output.action
            actionsPerformed.add(ActionWithOutcome(action, observation = output.observation, findings = output.findings))

            if (output.findings.isNotEmpty()) {
                observations.addAll(output.findings)
                logger.info(
                    "Agentic search findings ({}) for {}: {}",
                    output.findings.size, url, output.findings.joinToString("; ") { it.take(80) }
                )
            }

            val previousOpenQuestions = openQuestions.toSet()
            openQuestions = output.openQuestions
            val newlyAnswered = previousOpenQuestions - openQuestions.toSet()
            if (newlyAnswered.isNotEmpty()) {
                answeredQuestions.addAll(newlyAnswered)
            }

            if (output.captureRegions.isNotEmpty()) {
                cropCaptureRegions(rawScreenshot.bytes, output.captureRegions, capturedImages, capturedHashes)
            }

            val (imgWidth, imgHeight) = screenshotAnnotationService.getImageDimensions(rawScreenshot.bytes)

            when (action) {
                is NavigationAction.AnswerFound -> {
                    logger.info(
                        "Agentic search found answer after {} iterations ({} findings, {} captures) for {}: {}",
                        iteration, observations.size, capturedImages.size, url, action.answer.take(100)
                    )
                    return AgenticPageSearchResult(
                        answer = action.answer,
                        evidence = output.findings.lastOrNull() ?: observations.lastOrNull(),
                        contentDate = action.contentDate,
                        actionsPerformed = actionsPerformed,
                        observations = observations.toList(),
                        success = true,
                        totalTokenUsage = aggregatedTokenUsage,
                        discoveredUrls = discoveredUrls,
                        capturedImages = capturedImages.toList()
                    )
                }

                is NavigationAction.GiveUp -> {
                    val consecutiveGiveUps = actionsPerformed.takeLastWhile {
                        it.action is NavigationAction.GiveUp
                    }.size
                    val rejectionReason = evaluateGiveUpReadiness(actionsPerformed, ctx.scrollPercent)
                    if (rejectionReason != null) {
                        logger.info(
                            "Rejecting premature give_up at iteration {} for {} (attempt #{}) — {}",
                            iteration, url, consecutiveGiveUps, rejectionReason
                        )
                        val escalation = if (consecutiveGiveUps >= 2) {
                            " STOP trying give_up. You have been rejected $consecutiveGiveUps times. " +
                                    "Instead: use answer_found with whatever partial information you have gathered so far, " +
                                    "even if incomplete. Your findings and observations contain useful data."
                        } else ""
                        actionsPerformed[actionsPerformed.lastIndex] = actionsPerformed.last().copy(
                            outcome = "REJECTED: $rejectionReason$escalation"
                        )
                    } else {
                        logger.info(
                            "Agentic search gave up after {} iterations for {}: {}",
                            iteration, url, action.reason
                        )
                        return AgenticPageSearchResult(
                            answer = null,
                            evidence = null,
                            contentDate = null,
                            actionsPerformed = actionsPerformed,
                            observations = observations.toList(),
                            success = false,
                            totalTokenUsage = aggregatedTokenUsage,
                            discoveredUrls = discoveredUrls,
                            capturedImages = capturedImages.toList()
                        )
                    }
                }

                is NavigationAction.Click -> {
                    val targetElement = annotated.elementIndex[action.labelNumber]
                    if (targetElement != null) {
                        val desc = if (targetElement.text.isNotBlank()) {
                            "${targetElement.tag} '${targetElement.text.take(40)}'".trim()
                        } else {
                            action.reason.take(60)
                        }
                        actionsPerformed[actionsPerformed.lastIndex] =
                            actionsPerformed.last().copy(action = action.copy(elementDescription = desc))

                        if (desc in offPageClickedDescs) {
                            logger.debug("Skipping re-click on known off-page element '{}'", desc)
                            actionsPerformed[actionsPerformed.lastIndex] = actionsPerformed.last().copy(
                                outcome = "Element '$desc' already navigates OFF this page. " +
                                        "Do NOT click it again. Find the answer elsewhere on the CURRENT page, " +
                                        "or use answer_found / give_up."
                            )
                        } else {
                            val x = targetElement.centerX
                            val y = targetElement.centerY
                            logger.debug(
                                "Clicking label {} at ({},{}) - {} (reason: {})",
                                action.labelNumber, x, y, desc, action.reason
                            )

                            previousClickScreenshot = rawScreenshot.bytes

                            val clickResult = page.guardedClickAtCoordinates(x, y)
                            if (clickResult.navigatedAwayTo != null) {
                                logger.info(
                                    "Click on '{}' intercepted navigation to {} — page unchanged",
                                    desc, clickResult.navigatedAwayTo
                                )
                                val targetUrl = clickResult.navigatedAwayTo!!
                                discoveredUrls.add(targetUrl)
                                onLinkDiscovered?.invoke(targetUrl)
                                offPageClickedDescs.add(desc)
                                lastActionWasClick = false
                                val usedFind = actionsPerformed.any { it.action is NavigationAction.FindOnPage }
                                actionsPerformed[actionsPerformed.lastIndex] = actionsPerformed.last().copy(
                                    outcome = buildOffPageOutcome(targetUrl, offPageClickedDescs.size, usedFind)
                                )
                            } else {
                                lastActionWasClick = true
                            }
                        }
                    } else {
                        val maxLabel = interactiveElements.size - 1
                        logger.warn(
                            "VLM referenced non-existent label {}. Available: 0..{}",
                            action.labelNumber, maxLabel
                        )
                        actionsPerformed[actionsPerformed.lastIndex] = actionsPerformed.last().copy(
                            outcome = "ERROR: Label [${action.labelNumber}] does NOT exist. " +
                                    "Valid labels are 0 to $maxLabel. " +
                                    "Do NOT confuse page content (prices, phone numbers) with label badges."
                        )
                    }
                }

                is NavigationAction.ClickAt -> {
                    val viewportX = ((action.x / 1000.0) * imgWidth).toInt()
                    val viewportY = ((action.y / 1000.0) * imgHeight).toInt()
                    val desc = action.elementDescription
                        ?: action.reason.take(60).ifEmpty { "unlabeled element at (${action.x},${action.y})" }
                    actionsPerformed[actionsPerformed.lastIndex] =
                        actionsPerformed.last().copy(action = action.copy(elementDescription = desc))

                    if (desc in offPageClickedDescs) {
                        logger.debug("Skipping re-click on known off-page element '{}'", desc)
                        actionsPerformed[actionsPerformed.lastIndex] = actionsPerformed.last().copy(
                            outcome = "Element '$desc' already navigates OFF this page. " +
                                    "Do NOT click it again. Find the answer elsewhere on the CURRENT page, " +
                                    "or use answer_found / give_up."
                        )
                    } else {
                        logger.debug(
                            "ClickAt normalized ({},{}) -> viewport ({},{}) - {} (reason: {})",
                            action.x, action.y, viewportX, viewportY, desc, action.reason
                        )

                        previousClickScreenshot = rawScreenshot.bytes

                        try {
                            val clickResult = page.guardedClickAtCoordinates(viewportX, viewportY)
                            if (clickResult.navigatedAwayTo != null) {
                                logger.info(
                                    "ClickAt on '{}' intercepted navigation to {} — page unchanged",
                                    desc, clickResult.navigatedAwayTo
                                )
                                val targetUrl = clickResult.navigatedAwayTo!!
                                discoveredUrls.add(targetUrl)
                                onLinkDiscovered?.invoke(targetUrl)
                                offPageClickedDescs.add(desc)
                                lastActionWasClick = false
                                val usedFind = actionsPerformed.any { it.action is NavigationAction.FindOnPage }
                                actionsPerformed[actionsPerformed.lastIndex] = actionsPerformed.last().copy(
                                    outcome = buildOffPageOutcome(targetUrl, offPageClickedDescs.size, usedFind)
                                )
                            } else {
                                lastActionWasClick = true
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.warn(
                                "ClickAt failed at viewport ({},{}): {}",
                                viewportX, viewportY, e.message
                            )
                        }
                    }
                }

                is NavigationAction.FindOnPage -> {
                    logger.debug("FindOnPage: keywords={}", action.keywords)
                    try {
                        if (action.keywords.isEmpty()) {
                            actionsPerformed[actionsPerformed.lastIndex] = actionsPerformed.last().copy(
                                outcome = "No keywords provided. Provide keywords to search for."
                            )
                        } else {
                            val (counts, stemmedMatches) = coroutineScope {
                                val countsDeferred = async { page.countTextMatches(action.keywords) }
                                val stemmedDeferred = async {
                                    try {
                                        val pageText = page.extractTextContent()
                                        pageTextSearchService.search(pageText, action.keywords)
                                    } catch (e: Exception) {
                                        logger.debug("Stemmed text search failed, proceeding with exact only: {}", e.message)
                                        emptyMap()
                                    }
                                }
                                Pair(countsDeferred.await(), stemmedDeferred.await())
                            }
                            lastFindCounts = lastFindCounts + counts

                            val countsDesc = counts.entries.joinToString(", ") { (kw, c) ->
                                val hidden = c.total - c.visible
                                val base = if (hidden > 0) "$kw: ${c.visible} ($hidden hidden)" else "$kw: ${c.visible}"
                                val ctx = c.firstContext
                                if (ctx != null && c.total > 0) "$base (e.g. '$ctx')" else base
                            }
                            logger.info("find_on_page results for {}: {}", url, countsDesc)

                            val outcome = buildFindOnPageOutcome(
                                counts, stemmedMatches, action.keywords, countsDesc
                            )

                            val scrollTarget = pickAutoScrollTarget(counts, stemmedMatches, action.keywords)
                            if (scrollTarget != null) {
                                val scrolled = page.scrollToTextContent(scrollTarget)
                                if (scrolled) {
                                    logger.info("find_on_page auto-scrolled to '{}' on {}", scrollTarget, url)
                                    actionsPerformed[actionsPerformed.lastIndex] = actionsPerformed.last().copy(
                                        outcome = "$outcome Auto-scrolled to \"$scrollTarget\"."
                                    )
                                } else {
                                    actionsPerformed[actionsPerformed.lastIndex] = actionsPerformed.last().copy(
                                        outcome = outcome
                                    )
                                }
                                delay(POST_SCROLL_DELAY_MS)
                            } else {
                                actionsPerformed[actionsPerformed.lastIndex] = actionsPerformed.last().copy(
                                    outcome = outcome
                                )
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn("FindOnPage failed: {}", e.message)
                    }
                }

                is NavigationAction.ScrollToText -> {
                    logger.debug("ScrollToText: text='{}', occurrence={}", action.searchText, action.occurrence)
                    try {
                        if (action.searchText.isBlank()) {
                            actionsPerformed[actionsPerformed.lastIndex] = actionsPerformed.last().copy(
                                outcome = "No searchText provided."
                            )
                        } else {
                            val found = page.scrollToTextContent(action.searchText, action.occurrence)
                            if (found) {
                                logger.info("scroll_to_text found '{}' on {}", action.searchText, url)
                                val priorMatch = lastFindCounts[action.searchText]
                                val totalMatches = priorMatch?.total ?: 0
                                val outcomeText = buildString {
                                    append("Scrolled to \"${action.searchText}\".")
                                    if (totalMatches > 1) {
                                        append(" There are $totalMatches total matches on this page.")
                                    }
                                }
                                actionsPerformed[actionsPerformed.lastIndex] = actionsPerformed.last().copy(
                                    outcome = outcomeText
                                )
                            } else {
                                logger.debug("scroll_to_text: '{}' not found on {}", action.searchText, url)
                                actionsPerformed[actionsPerformed.lastIndex] = actionsPerformed.last().copy(
                                    outcome = "\"${action.searchText}\" not found on page."
                                )
                            }
                        }
                        delay(POST_SCROLL_DELAY_MS)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn("ScrollToText failed: {}", e.message)
                    }
                }

                is NavigationAction.Scroll -> {
                    logger.debug("Scroll: {} {}%", action.scrollDirection, action.scrollPercent)
                    try {
                        val scrollPx = (imgHeight * action.scrollPercent / 100.0).toInt()
                        val delta = when (action.scrollDirection) {
                            ScrollDirection.DOWN -> scrollPx
                            ScrollDirection.UP -> -scrollPx
                        }
                        page.scrollPage(delta)
                        val hasUsedFindOnPageBefore = actionsPerformed.any {
                            it.action is NavigationAction.FindOnPage
                        }
                        val scrollOutcome = buildString {
                            append("Scrolled ${action.scrollDirection.name.lowercase()} ${action.scrollPercent}%")
                            if (!hasUsedFindOnPageBefore) {
                                append(". IMPORTANT: You have not used find_on_page yet. Use find_on_page NEXT — it searches with stemming/fuzzy matching and auto-scrolls to the best match.")
                            }
                        }
                        actionsPerformed[actionsPerformed.lastIndex] = actionsPerformed.last().copy(
                            outcome = scrollOutcome
                        )
                        delay(POST_SCROLL_DELAY_MS)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn("Scroll failed: {}", e.message)
                    }
                }

                is NavigationAction.PeekFullPage -> {
                    logger.debug("Taking full-page peek screenshot for {}", url)
                    try {
                        val fullPageScreenshot = page.takeFullPageScreenshot()
                        val downscaled = downscaleScreenshot(fullPageScreenshot.bytes, PEEK_MAX_HEIGHT)
                        peekScreenshotOverride = IBrowserPage.Screenshot(
                            bytes = downscaled,
                            mimeType = ImageMimeType.JPEG
                        )
                        actionsPerformed[actionsPerformed.lastIndex] = actionsPerformed.last().copy(
                            outcome = "Full page overview captured. Study this image to understand " +
                                    "the overall page content and layout, then decide your next action."
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn("Full-page screenshot failed: {}", e.message)
                        actionsPerformed[actionsPerformed.lastIndex] = actionsPerformed.last().copy(
                            outcome = "peek_full_page failed: ${e.message}"
                        )
                    }
                }

                is NavigationAction.Type -> {
                    val targetElement = annotated.elementIndex[action.labelNumber]
                    if (targetElement != null) {
                        val desc = "${targetElement.tag} '${targetElement.text.take(40)}'".trim()
                        actionsPerformed[actionsPerformed.lastIndex] =
                            actionsPerformed.last().copy(action = action.copy(elementDescription = desc))

                        val x = targetElement.centerX
                        val y = targetElement.centerY
                        logger.debug(
                            "Typing '{}' into label {} at ({},{}) - {} (reason: {})",
                            action.text.take(30), action.labelNumber, x, y, desc, action.reason
                        )

                        previousClickScreenshot = rawScreenshot.bytes
                        lastActionWasClick = true

                        page.clickAtCoordinates(x, y)
                        delay(POST_CLICK_DELAY_MS)
                        page.typeText(action.text)
                        delay(POST_TYPE_DELAY_MS)
                    } else {
                        val maxLabel = interactiveElements.size - 1
                        logger.warn(
                            "VLM referenced non-existent label {} for type action. Available: 0..{}",
                            action.labelNumber, maxLabel
                        )
                        actionsPerformed[actionsPerformed.lastIndex] = actionsPerformed.last().copy(
                            outcome = "ERROR: Label [${action.labelNumber}] does NOT exist. " +
                                    "Valid labels are 0 to $maxLabel. " +
                                    "Do NOT confuse page content (prices, phone numbers) with label badges."
                        )
                    }
                }
            }
        }

        logger.info(
            "Agentic search exhausted {} iterations for {} without finding answer ({} observations, {} captures)",
            MAX_ITERATIONS, url, observations.size, capturedImages.size
        )
        return AgenticPageSearchResult(
            answer = null,
            evidence = null,
            contentDate = null,
            actionsPerformed = actionsPerformed,
            observations = observations.toList(),
            success = false,
            totalTokenUsage = aggregatedTokenUsage,
            discoveredUrls = discoveredUrls,
            capturedImages = capturedImages.toList()
        )
    }

    private val currencySymbols = setOf("$", "HK$", "£", "€", "¥", "US$", "A$", "S$")
    private val priceKeywords = setOf("price", "cost", "pricing", "fee", "fees", "rate")

    /**
     * Picks the best keyword to auto-scroll to after find_on_page. Returns null if no
     * suitable scroll target exists (e.g., only hidden matches or zero matches everywhere).
     */
    internal fun pickAutoScrollTarget(
        counts: Map<String, IBrowserPage.TextMatchCounts>,
        stemmedMatches: Map<String, List<TextMatch>>,
        keywords: List<String>
    ): String? {
        val visibleCurrency = counts.entries
            .filter { (kw, c) -> kw in currencySymbols && c.visible > 0 }
            .maxByOrNull { it.value.visible }
        if (visibleCurrency != null) return visibleCurrency.key

        val firstVisibleExact = keywords.firstOrNull { kw ->
            counts[kw]?.visible?.let { it > 0 } == true
        }
        if (firstVisibleExact != null) return firstVisibleExact

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

        val hiddenOnlyKeywords = counts.entries
            .filter { (_, c) -> c.visible == 0 && c.total > 0 }
            .map { it.key }
        if (hiddenOnlyKeywords.isNotEmpty()) {
            val examples = hiddenOnlyKeywords.take(3).joinToString(", ") { "\"$it\"" }
            append(" TIP: $examples have hidden-only matches — expand [collapsed] elements or use scroll_to_text.")
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

    private fun buildOffPageOutcome(
        targetUrl: String,
        offPageCount: Int,
        hasUsedFindOnPage: Boolean
    ): String = buildString {
        append("Navigated OFF-PAGE to $targetUrl — recorded for separate investigation. ")
        if (offPageCount >= 2) {
            append("STOP clicking links — you have tried $offPageCount off-page links already. ")
            if (!hasUsedFindOnPage) {
                append("You have NOT searched the current page yet. ")
            }
            append("Use find_on_page with relevant keywords to search the CURRENT page content.")
        } else if (!hasUsedFindOnPage) {
            append("Do NOT click more links. Use find_on_page first to check if the information exists on this page.")
        } else {
            append("Look for the information elsewhere on the CURRENT page using scroll_to_text or by expanding [collapsed] elements.")
        }
    }

    /**
     * Returns null if give-up is acceptable, or a rejection reason if the agent
     * hasn't explored the page thoroughly enough.
     *
     * Requirements escalate to prevent premature give-up:
     * 1. find_on_page must have been used at least once.
     * 2. If find_on_page found visible matches, scroll_to_text must have been used.
     * 3. If find_on_page reported hidden matches, scroll_to_text or click must follow.
     * 4. The page must have been navigated beyond the initial viewport
     *    (via scroll, scroll_to_text, or reaching high scroll%).
     */
    private fun evaluateGiveUpReadiness(
        actions: List<ActionWithOutcome>,
        scrollPercent: Int
    ): String? {
        val usedFindOnPage = actions.any { it.action is NavigationAction.FindOnPage }
        if (!usedFindOnPage) {
            return "You must use find_on_page to search for relevant keywords before giving up. " +
                    "The current viewport may not show all page content."
        }

        val findOutcomes = actions.filter { it.action is NavigationAction.FindOnPage }
        val allZeroMatches = findOutcomes.all { entry ->
            val outcome = entry.outcome ?: ""
            !outcome.contains("hidden") && Regex("""\w+: (\d+)""").findAll(outcome).all { it.groupValues[1] == "0" }
        }

        if (allZeroMatches && findOutcomes.size >= 1) {
            return null
        }

        val hasAnyVisibleMatches = findOutcomes.any { entry ->
            val outcome = entry.outcome ?: ""
            Regex("""\w+: (\d+)""").findAll(outcome).any { it.groupValues[1] != "0" }
        }
        val hasHiddenMatches = findOutcomes.any { entry ->
            (entry.outcome ?: "").contains("hidden", ignoreCase = true)
        }
        val usedScrollToText = actions.any { it.action is NavigationAction.ScrollToText }

        if (hasAnyVisibleMatches && !usedScrollToText) {
            return "find_on_page found visible matches on this page but you haven't used scroll_to_text to navigate to them. " +
                    "Use scroll_to_text with a matched keyword (e.g. a currency symbol like \"HK$\" or \"$\") to jump directly to the content."
        }

        if (hasHiddenMatches) {
            val lastFindIndex = actions.indexOfLast { it.action is NavigationAction.FindOnPage }
            val triedToReveal = actions.drop(lastFindIndex + 1).any {
                it.action is NavigationAction.Click || it.action is NavigationAction.ClickAt || it.action is NavigationAction.ScrollToText
            }
            if (!triedToReveal) {
                return "find_on_page reported hidden matches behind collapsed elements. " +
                        "You must use scroll_to_text to jump to hidden content, or click to expand collapsed elements, before giving up."
            }
        }

        val hasScrolled = actions.any {
            it.action is NavigationAction.Scroll || it.action is NavigationAction.ScrollToText
        }
        val pageFullyInViewport = scrollPercent >= 95

        if (!hasScrolled && !pageFullyInViewport) {
            return "You have not scrolled or used scroll_to_text yet. " +
                    "The page extends beyond the current viewport (scroll position: $scrollPercent%). " +
                    "Use scroll_to_text with relevant keywords, or scroll to explore more of the page."
        }

        return null
    }

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

    private fun cropCaptureRegions(
        screenshotBytes: ByteArray,
        regions: List<CaptureRegion>,
        capturedImages: MutableList<CapturedImage>,
        capturedHashes: MutableSet<String>
    ) {
        val (imgWidth, imgHeight) = screenshotAnnotationService.getImageDimensions(screenshotBytes)
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

            val bytes = screenshotAnnotationService.cropToPng(screenshotBytes, x, y, w, h)

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
        return screenshotAnnotationService.downscaleToJpeg(
            imageBytes,
            maxHeight,
            (PEEK_JPEG_QUALITY * 100).toInt()
        )
    }
}
