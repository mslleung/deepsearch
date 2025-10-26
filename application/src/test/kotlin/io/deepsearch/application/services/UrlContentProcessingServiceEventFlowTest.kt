package io.deepsearch.application.services

import io.deepsearch.application.config.applicationTestModule
import io.deepsearch.domain.browser.IBrowserRuntimePool
import io.deepsearch.domain.models.valueobjects.WebpageLink
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension

class UrlContentProcessingServiceEventFlowTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(applicationTestModule)
    }

    private val browserRuntimePool by inject<IBrowserRuntimePool>()
    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val urlContentProcessingService by inject<IUrlContentProcessingService>()

    @Test
    fun `processUrlAsFlow emits LinkDiscoveryComplete before MarkdownExtractionComplete`() = runTest(testCoroutineDispatcher) {
        // Given
        val url = "https://www.example.com/"
        val runtime = browserRuntimePool.acquireRuntime()

        try {
            // When
            val events = urlContentProcessingService.processUrlAsFlow(url, runtime).toList()

            // Then
            assertTrue(events.isNotEmpty(), "Should emit at least one event")
            
            // Find event indices
            val linkEventIndex = events.indexOfFirst { it is IUrlContentProcessingService.UrlProcessingEvent.LinkDiscoveryComplete }
            val markdownEventIndex = events.indexOfFirst { it is IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete }
            
            // Verify both events are present
            assertTrue(linkEventIndex >= 0, "Should emit LinkDiscoveryComplete event")
            assertTrue(markdownEventIndex >= 0, "Should emit MarkdownExtractionComplete event")
            
            // Verify order: links come before markdown
            assertTrue(linkEventIndex < markdownEventIndex, "LinkDiscoveryComplete should be emitted before MarkdownExtractionComplete")
            
            // Verify event data
            val linkEvent = events[linkEventIndex] as IUrlContentProcessingService.UrlProcessingEvent.LinkDiscoveryComplete
            val markdownEvent = events[markdownEventIndex] as IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete
            
            assertEquals(url, linkEvent.url)
            assertEquals(url, markdownEvent.url)
            assertTrue(markdownEvent.markdown.isNotBlank(), "Markdown should not be blank")
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `processUrlAsFlow with query emits events in correct order`() = runTest(testCoroutineDispatcher) {
        // Given
        val url = "https://www.example.com/"
        val query = "What is this page about?"
        val runtime = browserRuntimePool.acquireRuntime()

        try {
            // When
            val events = urlContentProcessingService.processUrlAsFlow(url, query, runtime).toList()

            // Then
            assertTrue(events.size >= 2, "Should emit at least two events")
            
            // Verify first event is LinkDiscoveryComplete
            val firstEvent = events[0]
            assertTrue(
                firstEvent is IUrlContentProcessingService.UrlProcessingEvent.LinkDiscoveryComplete,
                "First event should be LinkDiscoveryComplete"
            )
            
            // Verify last event is MarkdownExtractionComplete
            val lastEvent = events.last()
            assertTrue(
                lastEvent is IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete,
                "Last event should be MarkdownExtractionComplete"
            )
        } finally {
            runtime.close()
        }
    }
}

