package io.deepsearch.application.searchorchestrators.agenticbrowsersearch

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A priority-ordered buffer for DiscoveredLinks in agentic search.
 * 
 * Links are ordered by score (descending - higher scores first).
 * Provides a Flow that emits links in priority order.
 * 
 * This allows downstream processing to handle high-priority links first,
 * which is important when reducing concurrency for rate limiting.
 */
class PriorityLinkChannel(
    private val defaultScore: Int
) {
    private val closed = AtomicBoolean(false)
    
    // PriorityBlockingQueue with comparator for descending score order
    private val queue = PriorityBlockingQueue<ScoredLink>(
        100,
        compareByDescending { it.effectiveScore }
    )
    
    // Notification channel to signal when new items are available
    private val notificationChannel = Channel<Unit>(Channel.CONFLATED)

    /**
     * Wrapper class for priority ordering.
     */
    private data class ScoredLink(
        val link: DiscoveredLink,
        val effectiveScore: Int
    )

    /**
     * Send a link to the priority buffer.
     */
    suspend fun send(link: DiscoveredLink) {
        if (closed.get()) return
        
        val effectiveScore = link.score ?: defaultScore
        queue.offer(ScoredLink(link, effectiveScore))
        notificationChannel.send(Unit)
    }

    /**
     * Close the channel. No more items can be sent after this.
     */
    fun close() {
        closed.set(true)
        notificationChannel.close()
    }

    /**
     * Check if the channel is closed.
     */
    fun isClosedForSend(): Boolean = closed.get()

    /**
     * Returns a Flow that emits links in priority order (highest score first).
     * The flow completes when the channel is closed and the queue is empty.
     */
    fun receiveAsFlow(): Flow<DiscoveredLink> = flow {
        while (true) {
            // Try to poll from queue
            val item = queue.poll()
            if (item != null) {
                emit(item.link)
            } else {
                // Queue is empty - check if we're done
                if (closed.get()) {
                    // Double-check queue is really empty (race condition protection)
                    val finalItem = queue.poll()
                    if (finalItem != null) {
                        emit(finalItem.link)
                    } else {
                        break // Done - channel closed and queue empty
                    }
                } else {
                    // Wait for notification of new items
                    val result = notificationChannel.receiveCatching()
                    if (result.isClosed) {
                        // Drain remaining items
                        while (true) {
                            val remaining = queue.poll() ?: break
                            emit(remaining.link)
                        }
                        break
                    }
                }
            }
        }
    }

    /**
     * Current size of the buffer.
     */
    fun size(): Int = queue.size
}
