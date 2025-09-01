package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.agents.IWebpageActionExtractor
import io.deepsearch.domain.agents.WebAction

/**
 * Placeholder ADK-backed implementation. Wire to your LLM tool of choice.
 * Produces simple heuristics: click first button/link if no prior actions.
 */
class WebpageActionExtractorAdkImpl : IWebpageActionExtractor {
    override suspend fun generate(input: IWebpageActionExtractor.Input): IWebpageActionExtractor.Output {
        val pi = input.pageInformation
        if (input.priorActions.size > 4) {
            return IWebpageActionExtractor.Output(
                proposals = emptyList(),
                stopRecommended = true,
                stopReason = "Action budget reached"
            )
        }

        val proposals = mutableListOf<IWebpageActionExtractor.ActionProposal>()

        // Basic heuristics: accept cookie banners or click obvious navigation
        val clickables = sequence {
            for (b in pi.actionSpace.buttons) {
                if (!b.text.isNullOrBlank()) yield(WebAction.ClickByText(b.text!!))
            }
            for (l in pi.actionSpace.links) {
                if (!l.href.isNullOrBlank()) yield(WebAction.ClickLinkByHref(l.href))
            }
        }.take(3).toList()

        for (a in clickables) {
            proposals.add(
                IWebpageActionExtractor.ActionProposal(
                    action = a,
                    rationale = "Likely leads to more relevant content",
                    expectedOutcome = "Change in page state or navigation"
                )
            )
        }

        return IWebpageActionExtractor.Output(
            proposals = proposals,
            stopRecommended = proposals.isEmpty(),
            stopReason = if (proposals.isEmpty()) "No actions identified" else null
        )
    }
}


