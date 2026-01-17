package io.deepsearch.domain.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.Closeable

interface IApplicationCoroutineScope : Closeable {
    val scope: CoroutineScope
}

class ApplicationCoroutineScope(
    dispatchers: IDispatcherProvider
) : IApplicationCoroutineScope {
    private val job = SupervisorJob()
    override val scope: CoroutineScope = CoroutineScope(job + dispatchers.io)
    
    override fun close() {
        job.cancel()
    }
}
