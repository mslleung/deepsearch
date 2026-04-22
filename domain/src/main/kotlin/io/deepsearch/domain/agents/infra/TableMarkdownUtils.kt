package io.deepsearch.domain.agents.infra

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Shared utilities for converting HTML tables to markdown pipe-table format.
 * Handles colspan and rowspan by building a full grid and duplicating values.
 */
object TableMarkdownUtils {

    /**
     * Transforms HTML `<table>` blocks within a string to markdown pipe-tables,
     * leaving non-table text untouched.
     */
    fun transformHTMLTablesToMarkdown(text: String): String {
        val pattern = Regex("(?is)<table\\b[\\s\\S]*?</table>")
        return pattern.replace(text) { match ->
            val tableHtml = match.value
            val doc = Jsoup.parseBodyFragment(tableHtml)
            val table = doc.selectFirst("table")
            if (table != null) {
                val mdBlock = buildString {
                    val caption = table.selectFirst("caption")?.text()?.trim()
                    if (!caption.isNullOrBlank()) {
                        appendLine(caption)
                    }
                    appendLine(tableToMarkdown(table))
                }.trimEnd()
                "\n$mdBlock\n"
            } else {
                tableHtml
            }
        }.trim()
    }

    /**
     * Converts an HTML `<table>` element to a markdown pipe-table.
     * Builds a full grid handling both colspan and rowspan by duplicating values.
     */
    fun tableToMarkdown(table: Element): String {
        val grid: MutableList<MutableList<String>> = mutableListOf()
        var currentRowIndex = 0

        val tableRows = table.select("tr")
        if (tableRows.isEmpty()) return ""

        tableRows.forEach { tr ->
            if (grid.size <= currentRowIndex) {
                grid.add(mutableListOf())
            }

            var colIndex = 0

            fun nextFreeCol(): Int {
                var idx = colIndex
                val row = grid[currentRowIndex]
                while (idx < row.size && row[idx].isNotEmpty()) {
                    idx++
                }
                return idx
            }

            tr.select("th, td").forEach { cell ->
                colIndex = nextFreeCol()
                val text = cell.text().trim()
                val colSpan = cell.attr("colspan").toIntOrNull() ?: 1
                val rowSpan = cell.attr("rowspan").toIntOrNull() ?: 1

                val endColExclusive = colIndex + colSpan
                val endRowExclusive = currentRowIndex + rowSpan

                for (r in currentRowIndex until endRowExclusive) {
                    while (grid.size <= r) grid.add(mutableListOf())
                    val rowList = grid[r]
                    if (rowList.size < endColExclusive) {
                        repeat(endColExclusive - rowList.size) { rowList.add("") }
                    }
                    for (c in colIndex until endColExclusive) {
                        rowList[c] = text
                    }
                }

                colIndex = endColExclusive
            }

            currentRowIndex++
        }

        val numCols = grid.maxOfOrNull { it.size } ?: 0
        if (numCols == 0) return ""

        grid.forEach { r ->
            if (r.size < numCols) {
                repeat(numCols - r.size) { r.add("") }
            }
        }

        val allTrs = table.select("tr").toList()
        val theadTrs = table.select("thead tr").toList()
        val headerIndex = when {
            theadTrs.isNotEmpty() -> allTrs.indexOf(theadTrs.last()).coerceAtLeast(0)
            allTrs.firstOrNull { it.select("th").isNotEmpty() } != null ->
                allTrs.indexOfFirst { it.select("th").isNotEmpty() }.coerceAtLeast(0)
            else -> 0
        }

        val header = grid.getOrNull(headerIndex) ?: MutableList(numCols) { "" }
        val dataRows = grid.filterIndexed { idx, _ -> idx != headerIndex }

        fun escapeMd(text: String): String =
            text.replace("|", "\\|")
                .replace('\n', ' ')
                .trim()

        fun renderLine(cells: List<String>): String =
            "| " + cells.map { escapeMd(it) }.joinToString(" | ") + " |"

        val separator = "| " + List(numCols) { "---" }.joinToString(" | ") + " |"

        val sb = StringBuilder()
        sb.appendLine(renderLine(header))
        sb.appendLine(separator)
        dataRows.forEach { row -> sb.appendLine(renderLine(row)) }

        return sb.toString().trimEnd()
    }
}
