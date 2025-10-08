package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

object WebpagePopupTable : Table("webpage_popups") {
    val pageHash = varchar("page_hash", length = 128)
    val popupXPaths = text("popup_xpaths") // JSON array of XPath strings

    init {
        // Unique index
        index(true, pageHash)
    }

    override val primaryKey = PrimaryKey(pageHash)
}
