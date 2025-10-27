package io.deepsearch.application.services

import io.deepsearch.domain.agents.INavigationElementIdentificationAgent
import io.deepsearch.domain.agents.NavigationElementIdentificationInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.entities.WebpageSemanticElement
import io.deepsearch.domain.models.valueobjects.SemanticElements
import io.deepsearch.domain.repositories.IWebpageNavigationElementRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import kotlin.time.ExperimentalTime

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

    private suspend fun extractNavigationalElements(webpage: IBrowserPage): SemanticElements {
        val html = webpage.getFullHtml()

        val pageHash = MessageDigest.getInstance("SHA-256").digest(html.toByteArray())

        val cached = webpageNavigationElementRepository.findByHash(pageHash)
        if (cached != null) {
            logger.debug("Using cached navigation elements")
            return cached.elements
        }

        val navigationElements = identifyViaAgent(html)

        cacheNavigationElements(pageHash, navigationElements)
        return navigationElements
    }

    private suspend fun identifyViaAgent(
        html: String
    ): SemanticElements {
        val identificationResult = navigationElementIdentificationAgent.generate(
            NavigationElementIdentificationInput(
                html = html
            )
        )
        return identificationResult.elements
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun cacheNavigationElements(
        pageHash: ByteArray,
        elements: SemanticElements
    ) {
        webpageNavigationElementRepository.upsert(
            WebpageSemanticElement(
                pageHash = pageHash,
                elements = elements
            )
        )
    }

    private suspend fun removeElementsIfPresent(
        webpage: IBrowserPage,
        elements: SemanticElements
    ) {
        // Remove each navigation element individually
        elements.header?.let { element ->
            try {
                logger.debug("Removing header via XPath: {}", element.xpath)
                webpage.removeElement(element.xpath)
            } catch (e: Exception) {
                logger.warn("Failed to remove header at {}: {}", element.xpath, e.message)
            }
        }

        elements.footer?.let { element ->
            try {
                logger.debug("Removing footer via XPath: {}", element.xpath)
                webpage.removeElement(element.xpath)
            } catch (e: Exception) {
                logger.warn("Failed to remove footer at {}: {}", element.xpath, e.message)
            }
        }

        elements.navSidebar?.let { element ->
            try {
                logger.debug("Removing navigation sidebar via XPath: {}", element.xpath)
                webpage.removeElement(element.xpath)
            } catch (e: Exception) {
                logger.warn("Failed to remove navigation sidebar at {}: {}", element.xpath, e.message)
            }
        }

        elements.breadcrumb?.let { element ->
            try {
                logger.debug("Removing breadcrumb via XPath: {}", element.xpath)
                webpage.removeElement(element.xpath)
            } catch (e: Exception) {
                logger.warn("Failed to remove breadcrumb at {}: {}", element.xpath, e.message)
            }
        }

        elements.cookieBanner?.let { element ->
            try {
                logger.debug("Removing cookie banner via XPath: {}", element.xpath)
                webpage.removeElement(element.xpath)
            } catch (e: Exception) {
                logger.warn("Failed to remove cookie banner at {}: {}", element.xpath, e.message)
            }
        }

        elements.adBanners.forEach { element ->
            try {
                logger.debug("Removing ad banner via XPath: {}", element.xpath)
                webpage.removeElement(element.xpath)
            } catch (e: Exception) {
                logger.warn("Failed to remove ad banner at {}: {}", element.xpath, e.message)
            }
        }

        elements.popups.forEach { element ->
            try {
                logger.debug("Removing popup via XPath: {}", element.xpath)
                webpage.removeElement(element.xpath)
            } catch (e: Exception) {
                logger.warn("Failed to remove popup at {}: {}", element.xpath, e.message)
            }
        }
    }
}

