package io.deepsearch.domain.models.valueobjects

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SemanticElementType {
    @SerialName("HEADER")
    HEADER,
    @SerialName("FOOTER")
    FOOTER,
    @SerialName("SIDEBAR_LEFT")
    SIDEBAR_LEFT,
    @SerialName("SIDEBAR_RIGHT")
    SIDEBAR_RIGHT,
    @SerialName("NAVBAR")
    NAVBAR,
    @SerialName("BREADCRUMB")
    BREADCRUMB,
    @SerialName("STICKY_BAR")
    STICKY_BAR,
    @SerialName("CHAT_WIDGET")
    CHAT_WIDGET,
    @SerialName("COOKIE_BANNER")
    COOKIE_BANNER,
    @SerialName("AD_BANNER")
    AD_BANNER,
    @SerialName("POPUP")
    POPUP,
    @SerialName("MAIN_CONTENT")
    MAIN_CONTENT,
    @SerialName("OTHER")
    OTHER
}


