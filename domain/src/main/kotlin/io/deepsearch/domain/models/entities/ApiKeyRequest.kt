package io.deepsearch.domain.models.entities

import io.deepsearch.domain.models.valueobjects.ApiKeyId
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ApiKeyRequest(
    var id: Long? = null,
    val apiKeyId: ApiKeyId,
    val requestedAt: Instant = Clock.System.now()
)

