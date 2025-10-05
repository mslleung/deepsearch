package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

object WebpageNavigationElementTable : Table("webpage_navigation_elements") {
    val pageHash = binary("page_hash", 32)
    // JSON column storing list of navigation elements with xpath, type, and note
    val elementsJson = text("elements_json")

    override val primaryKey = PrimaryKey(pageHash)
}

