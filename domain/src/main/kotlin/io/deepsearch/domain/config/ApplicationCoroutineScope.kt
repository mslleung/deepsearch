package io.deepsearch.domain.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

interface IApplicationCoroutineScope {
    val scope: CoroutineScope
}

class ApplicationCoroutineScope(
    dispatchers: IDispatcherProvider
) : IApplicationCoroutineScope {
    override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.io)
}
