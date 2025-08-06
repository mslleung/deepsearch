package agents

import com.google.adk.agents.BaseAgent
import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.Part

object StrategyAgent {

    private val NAME: String = this::class.simpleName!!

    @JvmStatic
    val ROOT_AGENT: BaseAgent = initAgent()

    fun initAgent(): BaseAgent {
        return LlmAgent.builder()
            .name(NAME)
            .model("gemini-2.0-flash")
            .description("Agent to answer questions about the time and weather in a city.")
            .instruction(
                "You are a helpful agent who can answer user questions about the time and weather"
                        + " in a city."
            )
            .build()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val runner = InMemoryRunner(ROOT_AGENT)

        val session = runner
            .sessionService()
            .createSession(this::class.simpleName, this::class.simpleName)
            .blockingGet()

        println("StrategyAgent is ready! Type 'exit' to quit.")

        while (true) {
            print("You: ")

            val input = readln()
            if (input.isBlank()) continue
            if (input.lowercase() == "exit") break

            val userMsg = Content.fromParts(Part.fromText(input))
            val events = runner.runAsync(
                session,
                userMsg,
                RunConfig.builder().apply {
                    setStreamingMode(RunConfig.StreamingMode.NONE)
                    setMaxLlmCalls(100)
                }.build()
            )

            events.blockingForEach { event -> println("Agent: ${event.stringifyContent()}") }
        }
    }
}