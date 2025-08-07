package io.deepsearch.domain.agents;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;

// for testing Google ADK Dev UI, used by gradle task runAdkWebServer
public class StrategyAgentTest {

    private static String NAME = "strategy_agent";

    public static BaseAgent ROOT_AGENT = initAgent();

    public static BaseAgent initAgent() {
        return LlmAgent.builder()
                .name(NAME)
                .model("gemini-2.0-flash")
                .description("Agent to answer questions about the time and weather in a city.")
                .instruction(
                        "You are a helpful agent who can answer user questions about the time and weather"
                                + " in a city.")
                .build();
    }
}
