package io.deepsearch.domain.agents.infra

/**
 * A simple interface for an LLM agent.
 *
 * It should take an input object and return an output object.
 * The main reason for this class's existence is to simplify LLM operations as a simple function call.
 * In case we need to swap out Google ADK later, this method will prove valuable as it abstracts away the framework
 * implementations.
 */
interface IAgent<InputT : IAgent.IAgentInput, OutputT : IAgent.IAgentOutput> {

    // tagging interfaces
    interface IAgentInput
    interface IAgentOutput

    suspend fun generate(input: InputT): OutputT
}