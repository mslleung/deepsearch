package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

object WebpageNavigationElementTable : Table("webpage_navigation_elements") {
    val pageHash = varchar("page_hash", length = 128)
    // JSON column storing list of navigation elements with xpath, type, and note
    val elementsJson = text("elements_json")

    init {
        // Unique index
        index(true, pageHash)
    }

    override val primaryKey = PrimaryKey(pageHash)
}

