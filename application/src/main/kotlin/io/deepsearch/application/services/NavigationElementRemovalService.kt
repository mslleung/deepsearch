package io.deepsearch.application.services

import io.deepsearch.domain.agents.INavigationElementIdentificationAgent
import io.deepsearch.domain.agents.NavigationElementIdentificationInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.entities.WebpageNavigationElement
import io.deepsearch.domain.models.valueobjects.NavigationElementMatch
import io.deepsearch.domain.repositories.IWebpageNavigationElementRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.MessageDigest

interface INavigationElementRemovalService {
    /**
     * Removes navigational elements (header, footer, sidebars, navbars, sticky bars, etc.).
     * Uses a hash-based cache to avoid redundant LLM calls for similar page layouts.
     */
    suspend fun removeNavigationElements(webpage: IBrowserPage)
}

class NavigationElementRemovalService(
    private val navigationElementIdentificationAgent: INavigationElementIdentificationAgent,
    private val webpageNavigationElementRepository: IWebpageNavigationElementRepository
) : INavigationElementRemovalService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun removeNavigationElements(webpage: IBrowserPage) {
        val navigationElements = extractNavigationalElements(webpage)
        removeElementsIfPresent(webpage, navigationElements)
    }

    data class NavigationElements(
        val elements: List<NavigationElementMatch>
    )

    private suspend fun extractNavigationalElements(webpage: IBrowserPage): NavigationElements {
        val html = webpage.getFullHtml()

        val pageHash = MessageDigest.getInstance("SHA-256").digest(html.toByteArray())

        val cached = webpageNavigationElementRepository.findByHash(pageHash)
        if (cached != null) {
            logger.debug("Using cached navigation elements")
            return NavigationElements(elements = cached.elements)
        }

        val navigationElements = identifyViaAgent(html)

        cacheNavigationElements(pageHash, navigationElements)
        return navigationElements
    }

    private suspend fun identifyViaAgent(
        html: String
    ): NavigationElements {
        val identificationResult = navigationElementIdentificationAgent.generate(
            NavigationElementIdentificationInput(
                html = html
            )
        )
        return NavigationElements(
            elements = identificationResult.elements.map { NavigationElementMatch(xpath = it.xpath, type = it.type, note = it.note) }
        )
    }

    private suspend fun cacheNavigationElements(
        pageHash: ByteArray,
        elements: NavigationElements
    ) {
        webpageNavigationElementRepository.upsert(
            WebpageNavigationElement(
                pageHash = pageHash,
                elements = elements.elements
            )
        )
    }

    private suspend fun removeElementsIfPresent(
        webpage: IBrowserPage,
        elements: NavigationElements
    ) {
        for (match in elements.elements) {
            try {
                logger.debug("Removing navigation element [{}] via XPath: {}", match.type, match.xpath)
                webpage.removeElement(match.xpath)
            } catch (e: Exception) {
                logger.warn("Failed to remove navigation element [{}] at {}: {}", match.type, match.xpath, e.message)
            }
        }
    }
}

