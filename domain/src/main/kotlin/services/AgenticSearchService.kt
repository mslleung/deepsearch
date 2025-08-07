package io.deepsearch.domain.services

import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.Part
import io.deepsearch.domain.agents.SearchOrchestrationStateKeys
import io.deepsearch.domain.agents.SearchOrchestratorAgent
import io.deepsearch.domain.valueobjects.SearchQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import services.IBrowserService
import java.util.concurrent.ConcurrentHashMap

interface IAgenticSearchService {
    suspend fun performSearch(searchQuery: SearchQuery): String
}

class AgenticSearchService(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : IAgenticSearchService {
    override suspend fun performSearch(searchQuery: SearchQuery): String = withContext(dispatcher) {

        // For now use in-memory, later we need to consider what happens if the application restarts
        val runner = InMemoryRunner(SearchOrchestratorAgent)

        val session = runner
            .sessionService()
            .createSession(
                this::class.simpleName,
                this::class.simpleName,
                ConcurrentHashMap(mapOf(SearchOrchestrationStateKeys.URL_STATE_KEY to searchQuery.url)),
                null
            )
            .blockingGet()

        val events = runner.runAsync(
            session,
            Content.fromParts(Part.fromText(searchQuery.query)),
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

        finalResponse


//        browserService.createBrowser().use { browser ->
//
//            val browserContext = browser.createContext()
//            val page = browserContext.newPage()
//
//            page.navigate(searchQuery.url)
//
////            page.takeFullPageScreenshot()  // TODO
//
//            val runner = InMemoryRunner(StrategyAgent.ROOT_AGENT)
//
//            "Search completed for query: ${searchQuery.query} on URL: ${searchQuery.url}"
//        }
    }
}