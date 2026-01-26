package io.deepsearch.domain.services

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Converts semantic HTML `<table>` elements to GitHub-flavored markdown.
 * 
 * This is a pure programmatic conversion - no LLM needed because semantic tables
 * have well-defined structure with <thead>, <tbody>, <tr>, <th>, <td> tags.
 */
interface ISemanticTableConverter {
    /**
     * Convert a semantic HTML table to markdown.
     * 
     * @param tableHtml The outer HTML of a <table> element
     * @return GitHub-flavored markdown table, or empty string if conversion fails
     */
    fun convertToMarkdown(tableHtml: String): String
}

class SemanticTableConverter : ISemanticTableConverter {
    
    override fun convertToMarkdown(tableHtml: String): String {
        val doc = Jsoup.parseBodyFragment(tableHtml)
        val table = doc.selectFirst("table") ?: return ""
        
        val rows = mutableListOf<List<String>>()
        var headerRowCount = 0
        
        // Extract header rows from <thead> or first row with <th> elements
        val theadRows = table.select("thead tr")
        if (theadRows.isNotEmpty()) {
            theadRows.forEach { tr ->
                val cells = extractCellsFromRow(tr)
                if (cells.isNotEmpty()) {
                    rows.add(cells)
                    headerRowCount++
                }
            }
        } else {
            // Check if first row has <th> elements (implicit header)
            val firstRow = table.selectFirst("tr:has(th)")
            if (firstRow != null) {
                val cells = extractCellsFromRow(firstRow)
                if (cells.isNotEmpty()) {
                    rows.add(cells)
                    headerRowCount = 1
                }
            }
        }
        
        // Extract body rows
        val tbodyRows = table.select("tbody tr")
        val bodyRows = if (tbodyRows.isNotEmpty()) {
            tbodyRows
        } else {
            // No explicit <tbody>, use all <tr> except header
            table.select("tr").drop(headerRowCount)
        }
        
        bodyRows.forEach { tr ->
            // Skip rows that were already counted as header
            if (theadRows.isEmpty() && headerRowCount > 0 && tr == table.selectFirst("tr:has(th)")) {
                return@forEach
            }
            val cells = extractCellsFromRow(tr)
            if (cells.isNotEmpty()) {
                rows.add(cells)
            }
        }
        
        // If no rows at all, extract any rows we can find
        if (rows.isEmpty()) {
            table.select("tr").forEach { tr ->
                val cells = extractCellsFromRow(tr)
                if (cells.isNotEmpty()) {
                    rows.add(cells)
                }
            }
            headerRowCount = if (rows.isNotEmpty()) 1 else 0
        }
        
        if (rows.isEmpty()) return ""
        
        return buildMarkdownTable(rows, headerRowCount)
    }
    
    /**
     * Extract cell text values from a table row, handling colspan.
     */
    private fun extractCellsFromRow(tr: Element): List<String> {
        val cells = mutableListOf<String>()
        
        tr.select("th, td").forEach { cell ->
            val text = cleanCellText(cell.text())
            val colspan = cell.attr("colspan").toIntOrNull() ?: 1
            
            // Add the cell text
            cells.add(text)
            
            // For colspan > 1, add empty cells for the spanned columns
            repeat(colspan - 1) {
                cells.add("")
            }
        }
        
        return cells
    }
    
    /**
     * Clean cell text for markdown output.
     * - Trim whitespace
     * - Replace newlines with spaces
     * - Escape pipe characters
     */
    private fun cleanCellText(text: String): String {
        return text
            .trim()
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace("\\s+".toRegex(), " ")
            .replace("|", "\\|")
    }
    
    /**
     * Build a GitHub-flavored markdown table from rows.
     * 
     * @param rows List of rows, where each row is a list of cell values
     * @param headerRowCount Number of header rows (usually 1)
     */
    private fun buildMarkdownTable(rows: List<List<String>>, headerRowCount: Int): String {
        if (rows.isEmpty()) return ""
        
        // Determine column count (max cells across all rows)
        val colCount = rows.maxOf { it.size }
        if (colCount == 0) return ""
        
        val sb = StringBuilder()
        
        rows.forEachIndexed { idx, row ->
            // Pad row to have consistent column count
            val paddedRow = row + List(colCount - row.size) { "" }
            
            // Build row line
            sb.appendLine("| ${paddedRow.joinToString(" | ")} |")
            
            // Add separator line after header row(s)
            val effectiveHeaderRows = if (headerRowCount > 0) headerRowCount else 1
            if (idx == effectiveHeaderRows - 1) {
                sb.appendLine("| ${List(colCount) { "---" }.joinToString(" | ")} |")
            }
        }
        
        return sb.toString().trim()
    }
}
