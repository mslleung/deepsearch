package io.deepsearch.application.services

import io.deepsearch.domain.agents.ElementLabel
import io.deepsearch.domain.agents.IWebpageNavigationAgent
import io.deepsearch.domain.agents.IWebpageReconnaissanceAgent
import io.deepsearch.domain.agents.NavigationAction
import io.deepsearch.domain.agents.WebpageNavigationInput
import io.deepsearch.domain.agents.WebpageReconnaissanceInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.services.ScreenshotAnnotationService
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

data class AgenticPageSearchResult(
    val answer: String?,
    val evidence: String?,
    val intention: String?,
    val contentDate: String?,
    val actionsPerformed: List<NavigationAction>,
    val observations: List<String>,
    val success: Boolean,
    val totalTokenUsage: TokenUsageMetrics,
    val discoveredUrls: List<String> = emptyList()
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
    private val reconnaissanceAgent: IWebpageReconnaissanceAgent,
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
        private const val SCROLL_VIEWPORT_PIXELS = 600
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

        val actionsPerformed = mutableListOf<NavigationAction>()
        val observations = mutableListOf<String>()
        var openQuestions = listOf<String>()
        val answeredQuestions = mutableListOf<String>()
        val discoveredUrls = mutableListOf<String>()
        var aggregatedTokenUsage = TokenUsageMetrics.empty("gemini-3.1-flash-lite")

        // === Phase 1: Reconnaissance ===
        val pageText = page.extractTextContent()

        val reconOutput = reconnaissanceAgent.generate(
            WebpageReconnaissanceInput(pageText, query)
        )
        aggregatedTokenUsage += reconOutput.tokenUsage

        tokenUsageService.recordTokenUsage(
            sessionId = sessionId,
            agentName = "WebpageReconnaissanceAgent",
            modelName = reconOutput.tokenUsage.modelName,
            promptTokens = reconOutput.tokenUsage.promptTokens,
            outputTokens = reconOutput.tokenUsage.outputTokens,
            totalTokens = reconOutput.tokenUsage.totalTokens
        )

        if (reconOutput.scrollTargetText != null) {
            val scrolled = page.scrollToTextContent(
                reconOutput.scrollTargetText!!,
                reconOutput.scrollTargetOccurrence
            )
            if (scrolled) {
                delay(POST_SCROLL_DELAY_MS)
                logger.info(
                    "Reconnaissance: scrolled to '{}' (occurrence {})",
                    reconOutput.scrollTargetText, reconOutput.scrollTargetOccurrence
                )
            } else {
                logger.info(
                    "Reconnaissance: scroll target '{}' (occurrence {}) not found on page, staying at top",
                    reconOutput.scrollTargetText, reconOutput.scrollTargetOccurrence
                )
            }
        }

        val pageContext = reconOutput.pageStructure

        // === Phase 2: Navigation Loop ===
        var previousClickScreenshot: ByteArray? = null
        var previousActionOutcome: String? = null
        val failedClickDescs = mutableMapOf<String, Int>()
        var lastActionWasClick = false

        for (iteration in 1..MAX_ITERATIONS) {
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
            } else if (!lastActionWasClick && actionsPerformed.isNotEmpty()) {
                previousActionOutcome = null
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

            val input = WebpageNavigationInput(
                screenshot = annotatedScreenshot,
                query = query,
                pageContext = pageContext,
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

            when (action) {
                is NavigationAction.AnswerFound -> {
                    logger.info(
                        "Agentic search found answer after {} iterations ({} findings) for {}: {}",
                        iteration, observations.size, url, action.answer.take(100)
                    )
                    return AgenticPageSearchResult(
                        answer = action.answer,
                        evidence = action.evidence,
                        intention = action.intention,
                        contentDate = action.contentDate,
                        actionsPerformed = actionsPerformed,
                        observations = observations.toList(),
                        success = true,
                        totalTokenUsage = aggregatedTokenUsage,
                        discoveredUrls = discoveredUrls
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
                        intention = null,
                        contentDate = null,
                        actionsPerformed = actionsPerformed,
                        observations = observations.toList(),
                        success = false,
                        totalTokenUsage = aggregatedTokenUsage,
                        discoveredUrls = discoveredUrls
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
                        logger.warn(
                            "VLM referenced non-existent label {}. Available: 0..{}",
                            action.labelNumber, interactiveElements.size - 1
                        )
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
                    } catch (e: Exception) {
                        logger.warn("Scroll failed: {}", e.message)
                    }
                }
            }
        }

        logger.info(
            "Agentic search exhausted {} iterations for {} without finding answer ({} observations collected)",
            MAX_ITERATIONS, url, observations.size
        )
        return AgenticPageSearchResult(
            answer = null,
            evidence = null,
            intention = null,
            contentDate = null,
            actionsPerformed = actionsPerformed,
            observations = observations.toList(),
            success = false,
            totalTokenUsage = aggregatedTokenUsage,
            discoveredUrls = discoveredUrls
        )
    }

}
