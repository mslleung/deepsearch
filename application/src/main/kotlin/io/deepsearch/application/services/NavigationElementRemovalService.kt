package io.deepsearch.application.services

import io.deepsearch.domain.agents.INavigationElementIdentificationAgent
import io.deepsearch.domain.agents.NavigationElementIdentificationInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.entities.WebpageNavigationElement
import io.deepsearch.domain.repositories.IWebpageNavigationElementRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.MessageDigest

interface INavigationElementRemovalService {
    /**
     * Removes header and footer navigation elements from the webpage.
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
        val screenshot = webpage.takeScreenshot()
        val html = webpage.getFullHtml()
        
        val pageHash = MessageDigest.getInstance("SHA-256").digest(screenshot.bytes)

        // Check cache first
        val cached = webpageNavigationElementRepository.findByHash(pageHash)
        val (headerXPath, footerXPath) = if (cached != null) {
            logger.debug("Using cached navigation elements")
            Pair(cached.headerXPath, cached.footerXPath)
        } else {
            // Call agent to identify navigation elements
            logger.debug("Identifying navigation elements via agent")
            val identificationResult = navigationElementIdentificationAgent.generate(
                NavigationElementIdentificationInput(
                    screenshotBytes = screenshot.bytes,
                    mimetype = screenshot.mimeType,
                    html = html
                )
            )

            // Cache the result
            webpageNavigationElementRepository.upsert(
                WebpageNavigationElement(
                    pageHash = pageHash,
                    headerXPath = identificationResult.headerXPath,
                    footerXPath = identificationResult.footerXPath
                )
            )

            Pair(identificationResult.headerXPath, identificationResult.footerXPath)
        }

        // Remove header if found
        if (headerXPath != null) {
            logger.debug("Removing header via XPath: {}", headerXPath)
            webpage.removeElement(headerXPath)
        } else {
            logger.debug("No header element detected")
        }

        // Remove footer if found
        if (footerXPath != null) {
            logger.debug("Removing footer via XPath: {}", footerXPath)
            webpage.removeElement(footerXPath)
        } else {
            logger.debug("No footer element detected")
        }
    }
}

