package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

object WebpagePopupTable : Table("webpage_popups") {
    val pageHash = binary("page_hash", 32)
    val popupXPaths = text("popup_xpaths") // JSON array of XPath strings

    override val primaryKey = PrimaryKey(pageHash)
}
