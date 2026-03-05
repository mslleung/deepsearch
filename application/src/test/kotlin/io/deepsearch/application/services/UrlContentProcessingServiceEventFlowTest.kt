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

class UrlContentProcessingServiceEventFlowTest : IsolatedKoinTest() {

    @JvmField
    @RegisterExtension
    val koinTestExtension = IsolatedKoinExtension.create {
        modules(applicationTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val urlContentProcessingService by inject<IUrlContentProcessingService>()

    @Test
    fun `processUrlAsFlow for periodic indexing emits LinkDiscoveryComplete before MarkdownExtractionComplete`() = runTest(testCoroutineDispatcher) {
        // Given
        val url = "https://www.example.com/"

        // When - using PeriodicIndexSessionId for the indexing version
        val events = urlContentProcessingService.processUrlAsFlow(url, sessionId = PeriodicIndexSessionId(1L)).toList()

        // Then
        assertTrue(events.isNotEmpty(), "Should emit at least one event")
        
        val linkEventIndex = events.indexOfFirst { it is IUrlContentProcessingService.UrlProcessingEvent.LinkDiscoveryComplete }
        val markdownEventIndex = events.indexOfFirst { it is IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete }
        
        assertTrue(linkEventIndex >= 0, "Should emit LinkDiscoveryComplete event")
        assertTrue(markdownEventIndex >= 0, "Should emit MarkdownExtractionComplete event")
        assertTrue(linkEventIndex < markdownEventIndex, "LinkDiscoveryComplete should be emitted before MarkdownExtractionComplete")
        
        val linkEvent = events[linkEventIndex] as IUrlContentProcessingService.UrlProcessingEvent.LinkDiscoveryComplete
        val markdownEvent = events[markdownEventIndex] as IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete
        
        assertEquals(url, linkEvent.url)
        assertEquals(url, markdownEvent.url)
        assertTrue(markdownEvent.markdown.isNotBlank(), "Markdown should not be blank")
    }

    @Test
    fun `processUrlAsFlow with query emits AgenticSearchComplete for HTML pages`() = runTest(testCoroutineDispatcher) {
        // Given
        val url = "https://www.example.com/"
        val query = "What is this page about?"

        // When - using QuerySessionId triggers agentic search for HTML pages
        val events = urlContentProcessingService.processUrlAsFlow(url, query, sessionId = QuerySessionId("test-session-id")).toList()

        // Then
        assertTrue(events.isNotEmpty(), "Should emit at least one event")
        
        val linkEvents = events.filterIsInstance<IUrlContentProcessingService.UrlProcessingEvent.LinkDiscoveryComplete>()
        val agenticEvents = events.filterIsInstance<IUrlContentProcessingService.UrlProcessingEvent.AgenticSearchComplete>()
        
        assertTrue(linkEvents.isNotEmpty(), "Should emit LinkDiscoveryComplete event")
        assertTrue(agenticEvents.isNotEmpty(), "Should emit AgenticSearchComplete event for query sessions")
        
        val agenticEvent = agenticEvents.first()
        assertEquals(url, agenticEvent.url)
    }
}


