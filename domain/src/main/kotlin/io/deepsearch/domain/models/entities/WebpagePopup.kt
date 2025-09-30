package io.deepsearch.domain.models.entities

/**
 * Domain entity representing a cached popup container identification result.
 * The pageHash is derived from the screenshot bytes to identify similar page layouts.
 */
data class WebpagePopup(
    val pageHash: ByteArray,
    val popupXPaths: List<String>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WebpagePopup) return false

        if (!pageHash.contentEquals(other.pageHash)) return false
        if (popupXPaths != other.popupXPaths) return false

        return true
    }

    override fun hashCode(): Int {
        var result = pageHash.contentHashCode()
        result = 31 * result + popupXPaths.hashCode()
        return result
    }
}
