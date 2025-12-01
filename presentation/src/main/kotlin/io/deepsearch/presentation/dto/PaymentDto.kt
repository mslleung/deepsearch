package io.deepsearch.presentation.dto

import kotlinx.serialization.Serializable

@Serializable
data class PaymentConfigResponse(
    val publishableKey: String
)

@Serializable
data class CreateCheckoutSessionRequest(
    val planName: String,
    val successUrl: String,
    val cancelUrl: String
)

@Serializable
data class CreateCheckoutSessionResponse(
    val sessionId: String,
    val checkoutUrl: String
)

@Serializable
data class CreatePortalSessionRequest(
    val returnUrl: String
)

@Serializable
data class CreatePortalSessionResponse(
    val url: String
)
