package io.deepsearch.domain.agents.tools

import io.deepsearch.domain.agents.WebAction
import io.deepsearch.domain.browser.IBrowserPage

/**
 * Executes a [WebAction] on a given [IBrowserPage]. Keeps mapping logic in the domain.
 */
class WebActionExecutor {

    data class ExecutionResult(val success: Boolean, val description: String)

    fun execute(page: IBrowserPage, action: WebAction): ExecutionResult {
        return when (action) {
            is WebAction.ClickLinkByHref -> ExecutionResult(
                success = page.clickLinkByHref(action.href),
                description = "ClickLinkByHref ${action.href}"
            )
            is WebAction.ClickByText -> ExecutionResult(
                success = page.clickByText(action.text),
                description = "ClickByText ${action.text}"
            )
            is WebAction.ClickBoundingBox -> {
                val cx = (action.x1 + action.x2) / 2.0
                val cy = (action.y1 + action.y2) / 2.0
                ExecutionResult(
                    success = page.clickAt(cx, cy),
                    description = "ClickBoundingBox center=(${cx},${cy})"
                )
            }
            is WebAction.FillInput -> ExecutionResult(
                success = page.fillInputByNameOrId(action.nameOrId, action.value),
                description = "FillInput ${action.nameOrId}"
            )
            is WebAction.SubmitForm -> ExecutionResult(
                success = page.submitFormByAction(action.actionHref),
                description = "SubmitForm ${action.actionHref ?: "(nearest)"}"
            )
            is WebAction.ScrollToY -> {
                page.scrollToY(action.y)
                ExecutionResult(success = true, description = "ScrollToY ${action.y}")
            }
        }
    }
}


