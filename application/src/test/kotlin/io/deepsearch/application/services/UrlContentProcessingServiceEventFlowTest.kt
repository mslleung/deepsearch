package io.deepsearch.application.services

import io.deepsearch.application.config.applicationTestModule
import io.deepsearch.domain.models.valueobjects.PeriodicIndexSessionId
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import io.deepsearch.domain.testing.IsolatedKoinTest
import io.deepsearch.domain.testing.IsolatedKoinExtension

class IndexingUrlProcessingServiceEventFlowTest : IsolatedKoinTest() {

    @JvmField
    @RegisterExtension
    val koinTestExtension = IsolatedKoinExtension.create {
        modules(applicationTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val indexingUrlProcessingService by inject<IIndexingUrlProcessingService>()

    @Test
    fun `processUrlAsFlow for periodic indexing emits LinkDiscoveryComplete before MarkdownExtractionComplete`() = runTest(testCoroutineDispatcher) {
        val url = "https://www.example.com/"

        val events = indexingUrlProcessingService.processUrlAsFlow(url, sessionId = PeriodicIndexSessionId(1L)).toList()

        assertTrue(events.isNotEmpty(), "Should emit at least one event")
        
        val linkEventIndex = events.indexOfFirst { it is UrlProcessingEvent.LinkDiscoveryComplete }
        val markdownEventIndex = events.indexOfFirst { it is UrlProcessingEvent.MarkdownExtractionComplete }
        
        assertTrue(linkEventIndex >= 0, "Should emit LinkDiscoveryComplete event")
        assertTrue(markdownEventIndex >= 0, "Should emit MarkdownExtractionComplete event")
        assertTrue(linkEventIndex < markdownEventIndex, "LinkDiscoveryComplete should be emitted before MarkdownExtractionComplete")
        
        val linkEvent = events[linkEventIndex] as UrlProcessingEvent.LinkDiscoveryComplete
        val markdownEvent = events[markdownEventIndex] as UrlProcessingEvent.MarkdownExtractionComplete
        
        assertEquals(url, linkEvent.url)
        assertEquals(url, markdownEvent.url)
        assertTrue(markdownEvent.markdown.isNotBlank(), "Markdown should not be blank")
    }
}

class QueryUrlProcessingServiceEventFlowTest : IsolatedKoinTest() {

    @JvmField
    @RegisterExtension
    val koinTestExtension = IsolatedKoinExtension.create {
        modules(applicationTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val queryUrlProcessingService by inject<IQueryUrlProcessingService>()

    @Test
    fun `processUrlAsFlow with query emits AgenticSearchComplete for HTML pages`() = runTest(testCoroutineDispatcher) {
        val url = "https://www.example.com/"
        val query = "What is this page about?"

        val events = queryUrlProcessingService.processUrlAsFlow(url, query, sessionId = QuerySessionId("test-session-id")).toList()

        assertTrue(events.isNotEmpty(), "Should emit at least one event")
        
        val linkEvents = events.filterIsInstance<UrlProcessingEvent.LinkDiscoveryComplete>()
        val agenticEvents = events.filterIsInstance<UrlProcessingEvent.AgenticSearchComplete>()
        
        assertTrue(linkEvents.isNotEmpty(), "Should emit LinkDiscoveryComplete event")
        assertTrue(agenticEvents.isNotEmpty(), "Should emit AgenticSearchComplete event for query sessions")
        
        val agenticEvent = agenticEvents.first()
        assertEquals(url, agenticEvent.url)
    }
}
