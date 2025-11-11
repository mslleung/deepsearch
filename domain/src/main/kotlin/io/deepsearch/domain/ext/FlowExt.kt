package io.deepsearch.domain.ext

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

/**
 * Chunks the flow emissions into lists of up to [chunkSize] items,
 * emitting a chunk when either:
 * - The chunk size is reached
 * - [timeoutMs] milliseconds have elapsed since the last emission
 *
 * The timeout resets after each chunk emission.
 * Empty chunks are not emitted (timeout with no items collected is skipped).
 * When upstream completes, any remaining items are flushed.
 *
 * @param chunkSize Maximum number of items per chunk
 * @param timeoutMs Timeout in milliseconds to wait before emitting accumulated items
 */
@OptIn(DelicateCoroutinesApi::class)
fun <T> Flow<T>.chunkedWithTimeout(
    chunkSize: Int,
    timeoutMs: Long
): Flow<List<T>> = flow {
    require(chunkSize > 0) { "chunkSize must be positive" }
    require(timeoutMs > 0) { "timeoutMs must be positive" }

    val buffer = mutableListOf<T>()
    val channel = Channel<T>(Channel.UNLIMITED)

    coroutineScope {
        // Launch a coroutine to collect from upstream and send to channel
        val collectorJob = launch {
            try {
                collect { item ->
                    channel.send(item)
                }
            } finally {
                channel.close()
            }
        }

        // Process items from channel with timeout logic
        while (true) {
            val item = withTimeoutOrNull(timeoutMs) {
                channel.receiveCatching().getOrNull()
            }

            if (item != null) {
                buffer.add(item)
                
                // Emit when buffer reaches chunk size
                if (buffer.size >= chunkSize) {
                    emit(buffer.toList())
                    buffer.clear()
                }
            } else {
                // Timeout occurred or channel closed
                if (channel.isClosedForReceive) {
                    // Channel closed - flush remaining items and exit
                    if (buffer.isNotEmpty()) {
                        emit(buffer.toList())
                        buffer.clear()
                    }
                    break
                } else {
                    // Timeout occurred - emit buffer if not empty
                    if (buffer.isNotEmpty()) {
                        emit(buffer.toList())
                        buffer.clear()
                    }
                    // Continue processing, timeout resets
                }
            }
        }

        collectorJob.cancel()
    }
}

