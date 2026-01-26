package io.deepsearch.domain.services

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Converts semantic HTML `<ul>` and `<ol>` list elements to GitHub-flavored markdown.
 * 
 * This is a pure programmatic conversion - no LLM needed because semantic lists
 * have well-defined structure with <ul>, <ol>, <li> tags.
 */
interface ISemanticListConverter {
    /**
     * Convert a semantic HTML list to markdown.
     * 
     * @param listHtml The outer HTML of a <ul> or <ol> element
     * @return GitHub-flavored markdown list, or empty string if conversion fails
     */
    fun convertToMarkdown(listHtml: String): String
}

class SemanticListConverter : ISemanticListConverter {
    
    companion object {
        private const val INDENT_SPACES = 2
    }
    
    override fun convertToMarkdown(listHtml: String): String {
        val doc = Jsoup.parseBodyFragment(listHtml)
        // Find the first list element (either <ul> or <ol>)
        val list = doc.selectFirst("ul, ol") ?: return ""
        
        return convertList(list, depth = 0).trim()
    }
    
    /**
     * Recursively convert a list element to markdown.
     * 
     * @param listElement The <ul> or <ol> element
     * @param depth Current nesting depth (0 = root)
     */
    private fun convertList(listElement: Element, depth: Int): String {
        val sb = StringBuilder()
        val isOrdered = listElement.tagName() == "ol"
        val indent = " ".repeat(depth * INDENT_SPACES)
        
        var itemIndex = 1
        
        // Process only direct <li> children
        listElement.children().forEach { child ->
            if (child.tagName() == "li") {
                val (text, nestedLists) = extractListItemContent(child)
                
                // Build the list marker
                val marker = if (isOrdered) "${itemIndex}." else "*"
                
                // Add the item line - always output marker for every <li>
                if (text.isNotBlank()) {
                    sb.appendLine("$indent$marker $text")
                } else {
                    // Empty text (either completely empty or only nested lists)
                    sb.appendLine("$indent$marker")
                }
                
                // Process nested lists
                nestedLists.forEach { nestedList ->
                    sb.append(convertList(nestedList, depth + 1))
                }
                
                itemIndex++
            }
        }
        
        return sb.toString()
    }
    
    /**
     * Extract text content and nested lists from a list item.
     * 
     * @return Pair of (cleaned text content, list of nested list elements)
     */
    private fun extractListItemContent(li: Element): Pair<String, List<Element>> {
        val nestedLists = li.children().filter { it.tagName() in listOf("ul", "ol") }
        
        // Clone the element to extract text without nested lists
        val clone = li.clone()
        clone.select("ul, ol").remove()
        
        val text = cleanItemText(clone.text())
        
        return text to nestedLists
    }
    
    /**
     * Clean list item text for markdown output.
     * - Trim whitespace
     * - Replace newlines with spaces
     * - Collapse multiple spaces
     */
    private fun cleanItemText(text: String): String {
        return text
            .trim()
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace("\\s+".toRegex(), " ")
    }
}
