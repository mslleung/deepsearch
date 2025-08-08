package io.deepsearch.domain.agents

import agents.QueryExpansionAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.Part
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class QueryExpansionAgentTest {

    @Test
    fun `simple query expansion`() = runTest {
        val runner = InMemoryRunner(QueryExpansionAgent())

        val session = runner
            .sessionService()
            .createSession(this::class.simpleName, this::class.simpleName)
            .blockingGet()

        val userMsg = Content.fromParts(Part.fromText("TODO"))
        val events = runner.runAsync(
            session,
            userMsg,
            RunConfig.builder().apply {
                setStreamingMode(RunConfig.StreamingMode.NONE)
                setMaxLlmCalls(100)
            }.build()
        )

        var finalResponse = ""
        events.blockingForEach { event ->
            if (event.finalResponse() && event.content().isPresent) {
                val content = event.content().get()
                if (content.parts().isPresent
                    && !content.parts().get().isEmpty()
                    && content.parts().get()[0].text().isPresent
                ) {
                    if (!event.partial().orElse(false)) {
                        finalResponse = content.parts().get()[0].text().get()
                    }
                }
            }
        }

        assertTrue { !finalResponse.isEmpty() }
    }


}