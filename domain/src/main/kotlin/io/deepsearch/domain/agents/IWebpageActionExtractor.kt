package io.deepsearch.domain.agents

import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.SearchQuery

/**
 * Agent that proposes the next best actions to take on the CURRENT webpage
 * to surface information relevant to the query. The output is a prioritized
 * list of actions together with rationale and expected outcome.
 */
interface IWebpageActionExtractor {

    data class Input(
        val searchQuery: SearchQuery,
        val pageInformation: IBrowserPage.PageInformation,
        val screenshotBytes: ByteArray,
        val priorActions: List<WebAction> = emptyList()
    )

    data class ActionProposal(
        val action: WebAction,
        val rationale: String,
        val expectedOutcome: String
    )

    data class Output(
        val proposals: List<ActionProposal>,
        val stopRecommended: Boolean = false,
        val stopReason: String? = null
    )

    suspend fun generate(input: Input): Output
}


