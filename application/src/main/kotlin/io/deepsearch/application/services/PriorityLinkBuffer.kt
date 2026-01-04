package io.deepsearch.application.services

import io.deepsearch.domain.ext.pathDepth
import io.deepsearch.domain.models.valueobjects.WebpageLink
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A priority-ordered buffer for WebpageLinks for periodic indexing.
 * 
 * Links are ordered by path depth (ascending - shallower paths first).
 * Provides a Flow that emits links in priority order.
 * 
 * This ensures high-level pages (like /about) are processed before 
 * deeper pages (like /blog/2024/01/my-post).
 */
class PriorityLinkBuffer {
    private val closed = AtomicBoolean(false)
    
    // PriorityBlockingQueue with comparator for ascending path depth order
    private val queue = PriorityBlockingQueue<DepthScoredLink>(
        100,
        compareBy { it.pathDepth }
    )
    
    // Notification channel to signal when new items are available
    private val notificationChannel = Channel<Unit>(Channel.CONFLATED)

    /**
     * Wrapper class for priority ordering by path depth.
     */
    private data class DepthScoredLink(
        val link: WebpageLink,
        val pathDepth: Int
    )

    /**
     * Send a link to the priority buffer.
     */
    suspend fun send(link: WebpageLink) {
        if (closed.get()) return
        
        val depth = link.pathDepth()
        queue.offer(DepthScoredLink(link, depth))
        notificationChannel.send(Unit)
    }

    /**
     * Close the buffer. No more items can be sent after this.
     */
    fun close() {
        closed.set(true)
        notificationChannel.close()
    }

    /**
     * Check if the buffer is closed.
     */
    fun isClosedForSend(): Boolean = closed.get()

    /**
     * Returns a Flow that emits links in priority order (lowest path depth first).
     * The flow completes when the buffer is closed and the queue is empty.
     */
    fun receiveAsFlow(): Flow<WebpageLink> = flow {
        while (true) {
            val item = queue.poll()
            if (item != null) {
                emit(item.link)
            } else {
                if (closed.get()) {
                    // Double-check queue is really empty (race condition protection)
                    val finalItem = queue.poll()
                    if (finalItem != null) {
                        emit(finalItem.link)
                    } else {
                        break // Done - buffer closed and queue empty
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

