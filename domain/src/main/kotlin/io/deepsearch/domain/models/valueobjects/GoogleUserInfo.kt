package io.deepsearch.domain.models.valueobjects

import kotlinx.serialization.Serializable

@Serializable
data class GoogleUserInfo(
    val id: String,
    val email: String,
    val name: String? = null,
    val picture: String? = null,
    val verified_email: Boolean? = null
)

