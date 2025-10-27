package io.deepsearch.domain.models.entities

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
data class WebpageIcon(
    val imageBytesHash: ByteArray,
    val label: String?, // null if the icon is blank or cannot be interpreted, this prevents repeated sending it to the LLM
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now()
)