package io.deepsearch.application.services

import io.deepsearch.domain.agents.ComputerUseResponse
import io.deepsearch.domain.agents.CuFunctionResult
import io.deepsearch.domain.agents.IComputerUseNavigationAgent
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

data class ComputerUseSearchResult(
    val answer: String?,
    val success: Boolean,
    val totalTokenUsage: TokenUsageMetrics,
    val iterations: Int,
    val actionsPerformed: List<String>
)

interface IComputerUseSearchService {
    suspend fun searchWithinPage(
        url: String,
        query: String
    ): ComputerUseSearchResult

    suspend fun searchWithinPage(
        page: IBrowserPage,
        url: String,
        query: String
    ): ComputerUseSearchResult
}

class ComputerUseSearchService(
    private val browserPool: IBrowserPool,
    private val cuAgent: IComputerUseNavigationAgent
) : IComputerUseSearchService {

    private val logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun searchWithinPage(
        url: String,
        query: String
    ): ComputerUseSearchResult {
        return browserPool.withPage { page ->
            page.navigate(url)
            page.waitForLoad()
            searchWithinPage(page, url, query)
        }
    }

    override suspend fun searchWithinPage(
        page: IBrowserPage,
        url: String,
        query: String
    ): ComputerUseSearchResult {
        cuAgent.resetSession()

        dismissCookieBanners(page)

        var totalTokenUsage = TokenUsageMetrics.empty(ModelIds.GEMINI_3_5_FLASH.modelId)
        val actionsPerformed = mutableListOf<String>()
        var iterations = 0

        val screenshot = page.takeScreenshot()
        val currentUrl = page.getUrl()

        var response = cuAgent.startSession(query, screenshot, currentUrl)
        iterations++
        totalTokenUsage = totalTokenUsage + extractTokenUsage(response)

        while (iterations <= MAX_ITERATIONS) {
            when (response) {
                is ComputerUseResponse.Answer -> {
                    val answer = response.text.takeIf { it.isNotBlank() && it != INFORMATION_NOT_FOUND }
                    return ComputerUseSearchResult(
                        answer = answer,
                        success = answer != null,
                        totalTokenUsage = totalTokenUsage,
                        iterations = iterations,
                        actionsPerformed = actionsPerformed
                    )
                }

                is ComputerUseResponse.Actions -> {
                    val functionResults = mutableListOf<CuFunctionResult>()

                    for (fc in response.functionCalls) {
                        val actionDesc = "${fc.name}(${fc.intent ?: fc.args})"
                        actionsPerformed.add(actionDesc)
                        logger.info("CU iteration {}: executing {}", iterations, actionDesc)

                        val error = executeAction(page, fc.name, fc.args)

                        delay(POST_ACTION_DELAY_MS)

                        val newScreenshot = page.takeScreenshot()
                        val newUrl = page.getUrl()

                        functionResults.add(
                            CuFunctionResult(
                                callId = fc.id,
                                name = fc.name,
                                screenshot = newScreenshot,
                                currentUrl = newUrl,
                                error = error
                            )
                        )
                    }

                    response = cuAgent.continueSession(functionResults)
                    iterations++
                    totalTokenUsage = totalTokenUsage + extractTokenUsage(response)
                }
            }
        }

        logger.warn("CU reached max iterations ({}) without finishing", MAX_ITERATIONS)
        return ComputerUseSearchResult(
            answer = null,
            success = false,
            totalTokenUsage = totalTokenUsage,
            iterations = iterations,
            actionsPerformed = actionsPerformed
        )
    }

    private suspend fun executeAction(
        page: IBrowserPage,
        name: String,
        args: Map<String, Any>
    ): String? {
        return try {
            val snapshot = page.captureDomSnapshot()
            val vw = snapshot.viewportWidth
            val vh = snapshot.viewportHeight

            when (name) {
                "click", "click_at", "double_click", "right_click", "middle_click" -> {
                    val x = denormalize(args.getInt("x"), vw)
                    val y = denormalize(args.getInt("y"), vh)
                    page.clickAtCoordinates(x, y)
                    null
                }

                "type", "type_text_at" -> {
                    val x = args.getIntOrNull("x")
                    val y = args.getIntOrNull("y")
                    val text = args["text"]?.toString() ?: ""

                    if (x != null && y != null) {
                        page.clickAtCoordinates(denormalize(x, vw), denormalize(y, vh))
                        delay(100)
                    }
                    page.typeText(text)
                    null
                }

                "scroll", "scroll_at" -> {
                    val x = denormalize(args.getInt("x"), vw)
                    val y = denormalize(args.getInt("y"), vh)
                    val direction = args["direction"]?.toString() ?: "down"
                    val amount = args.getIntOrNull("amount") ?: 3

                    val (deltaX, deltaY) = when (direction.lowercase()) {
                        "up" -> 0 to -(amount * SCROLL_STEP_PX)
                        "down" -> 0 to (amount * SCROLL_STEP_PX)
                        "left" -> -(amount * SCROLL_STEP_PX) to 0
                        "right" -> (amount * SCROLL_STEP_PX) to 0
                        else -> 0 to (amount * SCROLL_STEP_PX)
                    }
                    page.scrollElementAtCoordinates(x, y, deltaX, deltaY)
                    null
                }

                "navigate", "go_back", "go_forward" -> {
                    val url = args["url"]?.toString() ?: ""
                    logger.info("CU blocked cross-page navigation to: {}", url)
                    "Navigation blocked: staying on current page"
                }

                "wait" -> {
                    val seconds = args.getIntOrNull("seconds") ?: 1
                    delay(seconds * 1000L)
                    null
                }

                "move", "hover_at" -> {
                    null
                }

                else -> {
                    logger.warn("CU unknown action: {}", name)
                    "Unknown action: $name"
                }
            }
        } catch (e: Exception) {
            logger.error("CU action '{}' failed: {}", name, e.message)
            "Error: ${e.message}"
        }
    }

    private fun extractTokenUsage(response: ComputerUseResponse): TokenUsageMetrics =
        when (response) {
            is ComputerUseResponse.Actions -> response.tokenUsage
            is ComputerUseResponse.Answer -> response.tokenUsage
        }

    private fun denormalize(normalized: Int, actual: Int): Int =
        (normalized / 1000.0 * actual).toInt()

    private fun Map<String, Any>.getInt(key: String): Int {
        val value = this[key] ?: throw IllegalArgumentException("Missing required arg: $key")
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: throw IllegalArgumentException("Non-numeric arg $key: $value")
            else -> throw IllegalArgumentException("Unexpected type for arg $key: ${value::class}")
        }
    }

    private fun Map<String, Any>.getIntOrNull(key: String): Int? {
        val value = this[key] ?: return null
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }

    private suspend fun dismissCookieBanners(page: IBrowserPage) {
        val selectors = listOf(
            "[id*='cookie'] button[class*='accept'], [id*='cookie'] button[class*='agree']",
            "[class*='cookie'] button[class*='accept'], [class*='cookie'] button[class*='agree']",
            "#CybotCookiebotDialogBodyLevelButtonLevelOptinAllowAll",
            "button[data-cookiefirst-action='accept']",
            "[id*='cookie-consent'] button:first-of-type",
            "[class*='cookie-banner'] button:first-of-type",
            "[id*='gdpr'] button:first-of-type",
        )
        for (selector in selectors) {
            try {
                page.clickByCssSelector(selector)
                delay(COOKIE_DISMISS_DELAY_MS)
                logger.debug("Dismissed cookie banner via: {}", selector)
                return
            } catch (_: Exception) {
                // selector didn't match
            }
        }
    }

    companion object {
        private const val MAX_ITERATIONS = 15
        private const val POST_ACTION_DELAY_MS = 500L
        private const val SCROLL_STEP_PX = 300
        private const val COOKIE_DISMISS_DELAY_MS = 1000L
        private const val INFORMATION_NOT_FOUND = "INFORMATION_NOT_FOUND"
    }
}
