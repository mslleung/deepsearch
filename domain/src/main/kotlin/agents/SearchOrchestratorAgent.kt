package io.deepsearch.domain.agents

import agents.QueryExpansionAgent
import com.google.adk.agents.BaseAgent
import com.google.adk.agents.InvocationContext
import com.google.adk.agents.LlmAgent
import com.google.adk.events.Event
import io.reactivex.rxjava3.core.Flowable

class SearchOrchestratorAgent(
    private val queryExpansionAgent: QueryExpansionAgent
): BaseAgent(
    SearchOrchestratorAgent::class.simpleName,
    "The root agent that serves as the entry-point for agentic search." +
            " Responsible for orchestrating the search process.",
    listOf(queryExpansionAgent),
    null,
    null
) {

    override fun runAsyncImpl(invocationContext: InvocationContext): Flowable<Event> {
        return queryExpansionAgent.runAsync(invocationContext)
    }

    override fun runLiveImpl(invocationContext: InvocationContext): Flowable<Event> {
        TODO("Not yet implemented")
    }
}