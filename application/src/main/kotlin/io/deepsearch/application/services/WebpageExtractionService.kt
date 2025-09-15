package io.deepsearch.application.services

import io.deepsearch.domain.agents.IIconInterpreterAgent
import io.deepsearch.domain.agents.IconInterpreterInput
import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.TableIdentificationInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.entities.WebpageIcon
import io.deepsearch.domain.repositories.IWebpageIconRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode

class WebpageExtractionService(
    private val tableIdentificationAgent: ITableIdentificationAgent,
    private val iconInterpreterAgent: IIconInterpreterAgent,
    private val webpageIconRepository: IWebpageIconRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * Converts a webpage into text for downstream LLM processing
     */
    suspend fun extractWebpage(webpage: IBrowserPage): String = coroutineScope {
        // Load full HTML
        val html = webpage.getFullHtml()
        val doc = Jsoup.parse(html)

        // Icon processing: interpret and cache unique icons, then replace each icon node with its label
        val icons = webpage.extractIcons()
        val selectorToLabel: Map<String, String> = icons.map { icon ->
                async(ioDispatcher) {
                    val existing = webpageIconRepository.findByHash(icon.bytesHash)
                    val label = existing?.label ?: run {
                        val interpreted = iconInterpreterAgent.generate(
                            IconInterpreterInput(bytes = icon.bytes, mimeType = icon.mimeType)
                        )
                        val webpageIcon = WebpageIcon(
                            imageBytesHash = icon.bytesHash,
                            label = interpreted.label
                        )
                        webpageIconRepository.upsert(webpageIcon)
                        interpreted.label
                    }
                    val finalLabel: String = label?.takeIf { it.isNotBlank() } ?: return@async emptyList<Pair<String, String>>()
                    icon.selectors.map { selector -> selector to finalLabel }
                }
            }.awaitAll()
                .flatten()
                .toMap()

        selectorToLabel.forEach { (selector, label) ->
            val nodes = try {
                doc.select(selector)
            } catch (_: IllegalArgumentException) {
                // Skip invalid selectors for Jsoup
                emptyList()
            }
            nodes.forEach { el -> el.replaceWith(TextNode(label)) }
        }

        // Table processing: identify tables from full-page screenshot and remove them from the DOM.
        val fullScreenshot = webpage.takeFullPageScreenshot()
        val tableOutput = tableIdentificationAgent.generate(
            TableIdentificationInput(fullScreenshot.bytes, fullScreenshot.mimeType)
        )

        // Best-effort removal based on XPath tokens (Jsoup has no native XPath)
        tableOutput.tables.forEach { t ->
            val tokens = extractContainsTokensFromXPath(t.xpath)
            if (tokens.isNotEmpty()) {
                // Remove any element containing ALL tokens (best-effort approximation of the XPath)
                doc.allElements
                    .asSequence()
                    .filter { it.tagName() != "html" && it.tagName() != "body" }
                    .filter { e -> tokens.all { token -> e.text().contains(token, ignoreCase = true) } }
                    .toList() // materialize before mutation
                    .forEach { it.remove() }
            }
        }
        // Also remove any native <table> elements
        doc.select("table").forEach { it.remove() }

        // Text extraction: drop non-content elements (buttons/links/scripts/styles/nav/etc.) and return text
        doc.select("script,style,nav,footer,header,button,a,svg,img,form").forEach { it.remove() }

        val text = doc.body().text().trim()
        text
    }

    private fun extractContainsTokensFromXPath(xpath: String): List<String> {
        // Matches contains(., 'token') and contains(., "token") capturing token as group 1
        val regex = Regex("contains\\(\\.,[\\t\\n\\r ]*['\"]([^'\"]+)['\"]\\)")
        return regex.findAll(xpath).map { it.groupValues[1] }.filter { it.isNotBlank() }.toList()
    }
}