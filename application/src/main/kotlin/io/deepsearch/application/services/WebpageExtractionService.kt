package io.deepsearch.application.services

import io.deepsearch.domain.agents.IIconInterpreterAgent
import io.deepsearch.domain.agents.IconInterpreterInput
import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.ITableInterpretationAgent
import io.deepsearch.domain.agents.TableIdentificationInput
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.browser.IBrowserPage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.TextNode

interface IWebpageExtractionService {
    suspend fun extractWebpage(webpage: IBrowserPage): String
}

class WebpageExtractionService(
    private val tableIdentificationAgent: ITableIdentificationAgent,
    private val iconInterpreterAgent: IIconInterpreterAgent,
    private val tableInterpretationAgent: ITableInterpretationAgent,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : IWebpageExtractionService {

    /**
     * Converts a webpage into text for downstream LLM processing
     */
    override suspend fun extractWebpage(webpage: IBrowserPage): String = coroutineScope {
        // Load full HTML
        val html = webpage.getFullHtml()
        val doc = Jsoup.parse(html)

        processIcons(webpage, doc)

        // Table processing: identify tables from full-page screenshot and interpret them to markdown
        val fullScreenshot = webpage.takeFullPageScreenshot()
        val tableOutput = tableIdentificationAgent.generate(
            TableIdentificationInput(fullScreenshot.bytes, fullScreenshot.mimeType)
        )

        // Interpret each identified table by XPath
        val xpathToMarkdown: Map<String, String> = tableOutput.tables.mapNotNull { t ->
            val xpath = t.xpath
            try {
                val elementScreenshot = webpage.getElementScreenshotByXPath(xpath)
                val elementHtml = webpage.getElementHtmlByXPath(xpath)
                val md = tableInterpretationAgent.generate(
                    TableInterpretationInput(
                        screenshotBytes = elementScreenshot.bytes,
                        mimetype = elementScreenshot.mimeType,
                        auxiliaryInfo = t.auxiliaryInfo,
                        html = elementHtml,
                    )
                ).markdown
                xpath to md
            } catch (ex: Exception) {
                null
            }
        }.toMap()

        // Build final output by traversing nodes; replace identified tables with markdown
        val sb = StringBuilder()
        val body = doc.body()
        traverseAndAppend(body, sb, xpathToMarkdown)
        sb.toString().trim()
    }

    private fun extractContainsTokensFromXPath(xpath: String): List<String> {
        val regex = Regex("contains\\(\\.,\\s*['\"]([^'\"]+)['\"]\\)")
        return regex.findAll(xpath).map { it.groupValues[1] }.filter { it.isNotBlank() }.toList()
    }

    private fun traverseAndAppend(node: org.jsoup.nodes.Node, sb: StringBuilder, xpathToMarkdown: Map<String, String>) {
        when (node) {
            is TextNode -> {
                val text = node.text().trim()
                if (text.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(text)
                }
            }

            is org.jsoup.nodes.Element -> {
                // If this element matches an identified table by best-effort token matching, emit markdown once and skip children
                val elementText = node.text()
                val matchingMarkdown = xpathToMarkdown.entries.firstOrNull { (xpath, _) ->
                    val tokens = extractContainsTokensFromXPath(xpath)
                    tokens.isNotEmpty() && tokens.all { token -> elementText.contains(token, ignoreCase = true) }
                }?.value

                if (matchingMarkdown != null) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(matchingMarkdown.trim())
                    return
                }

                // Skip script/style to avoid code/css noise; otherwise include all text nodes
                val tag = node.tagName()
                if (tag in setOf("script", "style")) {
                    return
                }

                node.childNodes().forEach { child -> traverseAndAppend(child, sb, xpathToMarkdown) }
            }

            else -> {
                node.childNodes().forEach { child -> traverseAndAppend(child, sb, xpathToMarkdown) }
            }
        }
    }

    private suspend fun processIcons(webpage: IBrowserPage, doc: Document) = coroutineScope {
        val icons = webpage.extractIcons()

        val resolvedIcons = icons
            .map { icon ->
                async(ioDispatcher) {
                    Pair(icon.selectors, resolveIconLabel(icon))
                }
            }
            .awaitAll()

        for ((selectors, label) in resolvedIcons) {
            for (selector in selectors) {
                val nodes = doc.select(selector)
                if (label != null) {
                    nodes.forEach { el -> el.replaceWith(TextNode(label)) }
                } else {
                    nodes.forEach { el -> el.remove() }
                }
            }
        }
    }

    private suspend fun resolveIconLabel(icon: IBrowserPage.Icon): String? {
        val interpreterAgentOutput = iconInterpreterAgent.generate(
            IconInterpreterInput(bytes = icon.bytes, mimeType = icon.mimeType)
        )
        return interpreterAgentOutput.label?.takeIf { it.isNotBlank() }
    }
}