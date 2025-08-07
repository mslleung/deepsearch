package io.deepsearch.domain.agents

import com.google.adk.agents.BaseAgent
import com.google.adk.agents.InvocationContext
import com.google.adk.agents.LlmAgent
import com.google.adk.events.Event
import io.reactivex.rxjava3.core.Flowable

private val llmAgent: LlmAgent = LlmAgent.builder()
    .name("StrategyLLM")
    .model(ModelIds.GEMINI_2_0_FLASH_LITE.modelId)
    .description("A simple strategy agent for deep search operations.")
    .instruction("You are a helpful strategy agent. Please respond to the user's query in a helpful and informative manner.")
    .build()

object SearchOrchestratorAgent: BaseAgent(
    SearchOrchestratorAgent::class.simpleName,
    "The root agent that serves as the entry-point for agentic search." +
            " Responsible for orchestrating the search process.",
    listOf(llmAgent),
    null,
    null
) {

    override fun runAsyncImpl(invocationContext: InvocationContext): Flowable<Event> {
        return llmAgent.runAsync(invocationContext)
    }

    override fun runLiveImpl(invocationContext: InvocationContext): Flowable<Event> {
        TODO("Not yet implemented")
    }
}