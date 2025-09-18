package io.deepsearch.application.services

import io.deepsearch.domain.agents.IIconInterpreterAgent
import io.deepsearch.domain.agents.IconInterpreterInput
import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.ITableInterpretationAgent
import io.deepsearch.domain.agents.TableIdentificationInput
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.entities.WebpageIcon
import io.deepsearch.domain.repositories.IWebpageIconRepository
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
    private val webpageIconRepository: IWebpageIconRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : IWebpageExtractionService {

    /**
     * Converts a webpage into text for downstream LLM processing
     */
    override suspend fun extractWebpage(webpage: IBrowserPage): String = coroutineScope {
        // Load full HTML
        val html = webpage.getFullHtml()
        val doc = Jsoup.parse(html)

        replaceIconsWithTexts(webpage, doc)

        // Table processing: identify tables from full-page screenshot and interpret them to markdown
        val fullScreenshot = webpage.takeFullPageScreenshot()
        val tableOutput = tableIdentificationAgent.generate(
            TableIdentificationInput(fullScreenshot.bytes, fullScreenshot.mimeType)
        )

        // Interpret each identified table by XPath and capture element HTML for later replacement
        val tableReplacements: List<Pair<String, String>> = tableOutput.tables.mapNotNull { t ->
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
                elementHtml to md
            } catch (ex: Exception) {
                null
            }
        }

        // Replace matching table nodes in parsed Jsoup document with interpreted markdown
        for ((elementHtml, markdown) in tableReplacements) {
            val match = doc.allElements.firstOrNull { it.outerHtml() == elementHtml }
            match?.replaceWith(TextNode(markdown))
        }

        // Traverse DOM in document order, collecting text from TextNodes while skipping script/style
        val builder = StringBuilder()
        val stack = ArrayDeque<org.jsoup.nodes.Node>()
        stack.addLast(doc)

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node is TextNode) {
                val text = node.text().trim()
                if (text.isNotBlank()) {
                    if (builder.isNotEmpty()) builder.append('\n')
                    builder.append(text)
                }
            } else {
                val nodeName = node.nodeName().lowercase()
                if (nodeName == "script" || nodeName == "style") {
                    continue
                }
                val children = node.childNodes()
                for (i in children.size - 1 downTo 0) {
                    stack.addLast(children[i])
                }
            }
        }

        return@coroutineScope builder.toString()

    }


    private suspend fun replaceIconsWithTexts(webpage: IBrowserPage, doc: Document) = coroutineScope {
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
        val bytesHash = java.security.MessageDigest.getInstance("SHA-256").digest(icon.bytes)

        val existing = webpageIconRepository.findByHash(bytesHash)
        if (existing != null) {
            return existing.label?.takeIf { it.isNotBlank() }
        }

        val interpreterAgentOutput = iconInterpreterAgent.generate(
            IconInterpreterInput(bytes = icon.bytes, mimeType = icon.mimeType)
        )
        val label = interpreterAgentOutput.label?.takeIf { it.isNotBlank() }

        webpageIconRepository.upsert(
            WebpageIcon(
                imageBytesHash = bytesHash,
                label = label
            )
        )

        return label
    }
}