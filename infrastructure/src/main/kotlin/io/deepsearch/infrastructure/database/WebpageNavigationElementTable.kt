package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

object WebpageNavigationElementTable : Table("webpage_navigation_elements") {
    val pageHash = binary("page_hash", 32)
    val headerXPath = text("header_xpath").nullable()
    val footerXPath = text("footer_xpath").nullable()

    override val primaryKey = PrimaryKey(pageHash)
}

