package io.deepsearch.application.services

import io.deepsearch.domain.agents.CaptureRegion
import io.deepsearch.domain.agents.ElementLabel
import io.deepsearch.domain.agents.IWebpageNavigationAgent
import io.deepsearch.domain.agents.NavigationAction
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
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.imageio.ImageIO
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
    val actionsPerformed: List<NavigationAction>,
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
        sessionId: SessionId
    ): AgenticPageSearchResult
}

class AgenticWebpageSearchService(
    private val browserPool: IBrowserPool,
    private val webpageNavigationAgent: IWebpageNavigationAgent,
    private val screenshotAnnotationService: ScreenshotAnnotationService,
    private val tokenUsageService: ILlmTokenUsageService
) : IAgenticWebpageSearchService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val MAX_ITERATIONS = 12
        private const val MAX_FAILED_CLICKS = 2
        private const val POST_CLICK_DELAY_MS = 350L
        private const val POST_SCROLL_DELAY_MS = 150L
        private const val POST_TYPE_DELAY_MS = 500L
        private const val SCROLL_VIEWPORT_PIXELS = 600
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
        sessionId: SessionId
    ): AgenticPageSearchResult {
        logger.info("Starting agentic page search for query='{}' on url={}", query, url)

        dismissCookieBanner(page, url)

        val actionsPerformed = mutableListOf<NavigationAction>()
        val observations = mutableListOf<String>()
        var openQuestions = listOf<String>()
        val answeredQuestions = mutableListOf<String>()
        val discoveredUrls = mutableListOf<String>()
        val capturedImages = mutableListOf<CapturedImage>()
        val capturedHashes = mutableSetOf<String>()
        var aggregatedTokenUsage = TokenUsageMetrics.empty("gemini-3.1-flash-lite")

        // Navigation Loop
        var previousClickScreenshot: ByteArray? = null
        var previousActionOutcome: String? = null
        val failedClickDescs = mutableMapOf<String, Int>()
        var lastActionWasClick = false
        var peekScreenshotOverride: IBrowserPage.Screenshot? = null

        for (iteration in 1..MAX_ITERATIONS) {
            currentCoroutineContext().ensureActive()
            logger.debug("Agentic search iteration {}/{} for {}", iteration, MAX_ITERATIONS, url)

            val (interactiveElements, rawScreenshot) = coroutineScope {
                val elementsDeferred = async { page.getInteractiveElements() }
                val screenshotDeferred = async { page.takeScreenshot() }
                Pair(elementsDeferred.await(), screenshotDeferred.await())
            }
            logger.debug("Found {} interactive elements", interactiveElements.size)

            if (previousClickScreenshot != null && lastActionWasClick) {
                val changed = screenshotAnnotationService.hasVisualChange(
                    previousClickScreenshot, rawScreenshot.bytes
                )
                val lastAction = actionsPerformed.last()
                val clickDesc = when (lastAction) {
                    is NavigationAction.Click -> lastAction.elementDescription ?: ""
                    is NavigationAction.ClickAt -> lastAction.elementDescription ?: "(${lastAction.x},${lastAction.y})"
                    is NavigationAction.Type -> lastAction.elementDescription ?: ""
                    else -> ""
                }
                if (changed) {
                    previousActionOutcome =
                        "Your last action ($lastAction) caused a VISIBLE CHANGE on the page."
                    failedClickDescs.remove(clickDesc)
                } else {
                    val failCount = failedClickDescs.merge(clickDesc, 1, Int::plus)!!

                    previousActionOutcome = if (failCount >= MAX_FAILED_CLICKS) {
                        "Element '$clickDesc' has FAILED $failCount times — it is NOT clickable. " +
                                "STOP trying it. Drop unanswerable questions from openQuestions and " +
                                "use answer_found with the findings you already have."
                    } else {
                        "Your last action ($lastAction) did NOT produce any visible change. " +
                                "Try a different element or approach."
                    }
                }
                logger.debug("Visual diff after click: changed={}", changed)
            } else if (!lastActionWasClick && actionsPerformed.isNotEmpty() && previousActionOutcome == null) {
                // Don't reset outcome if already set by SearchText/PeekFullPage handlers
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

            val elementLabels = interactiveElements.map { el ->
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
                previousActionOutcome = previousActionOutcome,
                elementLabels = elementLabels,
                answeredQuestions = answeredQuestions.toList(),
                openQuestions = openQuestions
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
            actionsPerformed.add(action)

            val finding = output.finding
            if (finding != null) {
                observations.add(finding)
                logger.info(
                    "Agentic search finding #{} for {}: {}",
                    observations.size, url, finding.take(100)
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

            when (action) {
                is NavigationAction.AnswerFound -> {
                    logger.info(
                        "Agentic search found answer after {} iterations ({} findings, {} captures) for {}: {}",
                        iteration, observations.size, capturedImages.size, url, action.answer.take(100)
                    )
                    return AgenticPageSearchResult(
                        answer = action.answer,
                        evidence = finding ?: observations.lastOrNull(),
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

                is NavigationAction.Click -> {
                    val targetElement = annotated.elementIndex[action.labelNumber]
                    if (targetElement != null) {
                        val desc = "${targetElement.tag} '${targetElement.text.take(40)}'".trim()
                        actionsPerformed[actionsPerformed.lastIndex] =
                            action.copy(elementDescription = desc)

                        val x = targetElement.centerX
                        val y = targetElement.centerY
                        logger.debug(
                            "Clicking label {} at ({},{}) - {} (reason: {})",
                            action.labelNumber, x, y, desc, action.reason
                        )

                        previousClickScreenshot = rawScreenshot.bytes
                        lastActionWasClick = true

                        page.clickAtCoordinates(x, y)
                        delay(POST_CLICK_DELAY_MS)

                        val currentUrl = page.getUrl()
                        if (currentUrl != url && !currentUrl.startsWith("about:")) {
                            logger.info(
                                "Click triggered navigation away from {} to {} — recording as discovered link and navigating back",
                                url, currentUrl
                            )
                            discoveredUrls.add(currentUrl)
                            page.navigate(url)
                            page.waitForLoad()
                            lastActionWasClick = false
                        }
                    } else {
                        val maxLabel = interactiveElements.size - 1
                        logger.warn(
                            "VLM referenced non-existent label {}. Available: 0..{}",
                            action.labelNumber, maxLabel
                        )
                        previousActionOutcome = "ERROR: Label [${action.labelNumber}] does NOT exist. " +
                                "Valid labels are 0 to $maxLabel. " +
                                "Do NOT confuse page content (prices, phone numbers) with label badges."
                    }
                }

                is NavigationAction.ClickAt -> {
                    val img = ImageIO.read(ByteArrayInputStream(rawScreenshot.bytes))
                    val viewportX = ((action.x / 1000.0) * img.width).toInt()
                    val viewportY = ((action.y / 1000.0) * img.height).toInt()
                    val desc = action.elementDescription ?: "unlabeled element at (${action.x},${action.y})"
                    actionsPerformed[actionsPerformed.lastIndex] =
                        action.copy(elementDescription = desc)

                    logger.debug(
                        "ClickAt normalized ({},{}) -> viewport ({},{}) - {} (reason: {})",
                        action.x, action.y, viewportX, viewportY, desc, action.reason
                    )

                    previousClickScreenshot = rawScreenshot.bytes
                    lastActionWasClick = true

                    try {
                        page.clickAtCoordinates(viewportX, viewportY)
                        delay(POST_CLICK_DELAY_MS)

                        val currentUrl = page.getUrl()
                        if (currentUrl != url && !currentUrl.startsWith("about:")) {
                            logger.info(
                                "ClickAt triggered navigation away from {} to {} — recording and navigating back",
                                url, currentUrl
                            )
                            discoveredUrls.add(currentUrl)
                            page.navigate(url)
                            page.waitForLoad()
                            lastActionWasClick = false
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

                is NavigationAction.Scroll -> {
                    logger.debug("Scrolling {}", action.direction)
                    try {
                        val delta = when (action.direction) {
                            io.deepsearch.domain.agents.ScrollDirection.DOWN -> SCROLL_VIEWPORT_PIXELS
                            io.deepsearch.domain.agents.ScrollDirection.UP -> -SCROLL_VIEWPORT_PIXELS
                        }
                        page.scrollPage(delta)
                        delay(POST_SCROLL_DELAY_MS)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn("Scroll failed: {}", e.message)
                    }
                }

                is NavigationAction.SearchText -> {
                    logger.debug("Searching for text: {}", action.searchTerms)
                    var matched: String? = null
                    for (term in action.searchTerms) {
                        if (page.scrollToTextContent(term)) {
                            matched = term
                            break
                        }
                    }
                    previousActionOutcome = if (matched != null) {
                        logger.info("search_text matched '{}' on {}", matched, url)
                        "search_text found \"$matched\". Viewport scrolled to match."
                    } else {
                        logger.debug("search_text found no matches for {} on {}", action.searchTerms, url)
                        "search_text found NO matches for: ${action.searchTerms.joinToString { "\"$it\"" }}"
                    }
                    delay(POST_SCROLL_DELAY_MS)
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
                        previousActionOutcome = "Full page overview captured. Study this image to understand " +
                                "the overall page content and layout, then decide your next action."
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn("Full-page screenshot failed: {}", e.message)
                        previousActionOutcome = "peek_full_page failed: ${e.message}"
                    }
                }

                is NavigationAction.Type -> {
                    val targetElement = annotated.elementIndex[action.labelNumber]
                    if (targetElement != null) {
                        val desc = "${targetElement.tag} '${targetElement.text.take(40)}'".trim()
                        actionsPerformed[actionsPerformed.lastIndex] =
                            action.copy(elementDescription = desc)

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
                        previousActionOutcome = "ERROR: Label [${action.labelNumber}] does NOT exist. " +
                                "Valid labels are 0 to $maxLabel. " +
                                "Do NOT confuse page content (prices, phone numbers) with label badges."
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
        val img = ImageIO.read(ByteArrayInputStream(screenshotBytes)) ?: return
        val sha256 = MessageDigest.getInstance("SHA-256")

        for (region in regions) {
            val x = ((region.x1 / 1000.0) * img.width).toInt().coerceIn(0, img.width - 1)
            val y = ((region.y1 / 1000.0) * img.height).toInt().coerceIn(0, img.height - 1)
            val x2 = ((region.x2 / 1000.0) * img.width).toInt().coerceIn(x + 1, img.width)
            val y2 = ((region.y2 / 1000.0) * img.height).toInt().coerceIn(y + 1, img.height)
            val w = x2 - x
            val h = y2 - y

            if (w < MIN_CAPTURE_SIZE_PX || h < MIN_CAPTURE_SIZE_PX) {
                logger.debug("Skipping tiny capture region {}x{} for: {}", w, h, region.relevance)
                continue
            }

            val cropped = img.getSubimage(x, y, w, h)
            val buf = ByteArrayOutputStream()
            ImageIO.write(cropped, "png", buf)
            val bytes = buf.toByteArray()

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
        val original = ImageIO.read(ByteArrayInputStream(imageBytes)) ?: return imageBytes
        val needsResize = original.height > maxHeight

        val buffered = if (needsResize) {
            val scale = maxHeight.toDouble() / original.height
            val newWidth = (original.width * scale).toInt().coerceAtLeast(1)
            val scaled = original.getScaledInstance(newWidth, maxHeight, Image.SCALE_SMOOTH)
            BufferedImage(newWidth, maxHeight, BufferedImage.TYPE_INT_RGB).also {
                it.graphics.drawImage(scaled, 0, 0, null)
            }
        } else {
            original
        }

        val output = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
            compressionQuality = PEEK_JPEG_QUALITY
        }
        writer.output = ImageIO.createImageOutputStream(output)
        writer.write(null, javax.imageio.IIOImage(buffered, null, null), param)
        writer.dispose()
        return output.toByteArray()
    }
}
