package io.deepsearch.domain.agents

/**
 * Canonical action model that the agent can request and the browser can execute.
 *
 * Keep this intentionally small and human-meaningful; expand incrementally.
 */
sealed interface WebAction {
    data class ClickLinkByHref(val href: String): WebAction
    data class ClickByText(val text: String, val roleHint: RoleHint = RoleHint.ANY): WebAction
    data class ClickBoundingBox(val x1: Double, val y1: Double, val x2: Double, val y2: Double): WebAction
    data class FillInput(val nameOrId: String, val value: String): WebAction
    data class SubmitForm(val actionHref: String?): WebAction
    data class ScrollToY(val y: Double): WebAction

    enum class RoleHint { LINK, BUTTON, ANY }
}


