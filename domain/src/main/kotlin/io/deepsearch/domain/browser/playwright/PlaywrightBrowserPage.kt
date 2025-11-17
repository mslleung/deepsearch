package io.deepsearch.domain.browser.playwright

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.options.ScreenshotType
import com.microsoft.playwright.options.LoadState
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.exceptions.BrowserNavigationException
import io.deepsearch.domain.exceptions.ConnectionRefusedException
import io.deepsearch.domain.exceptions.DnsResolutionException
import io.deepsearch.domain.exceptions.HttpClientErrorException
import io.deepsearch.domain.exceptions.HttpServerErrorException
import io.deepsearch.domain.exceptions.NetworkTimeoutException
import io.deepsearch.domain.exceptions.RedirectLoopException
import io.deepsearch.domain.exceptions.SslHandshakeException
import io.deepsearch.domain.exceptions.UrlProcessingException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.io.encoding.Base64
import kotlin.system.measureTimeMillis

/**
 * Playwright-backed implementation of a browser page.
 *
 * Uses Playwright locators and ARIA roles to build a human-oriented snapshot of the page,
 * keeping ARIA and DOM details internal to this adapter.
 */
class PlaywrightBrowserPage(
    private val page: Page,
    private val apiMutex: Mutex
) : IBrowserPage {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    init {
        page.onConsoleMessage { consoleMessage ->
            val message = consoleMessage.text()
            when (val type = consoleMessage.type()) {
                "log", "info" -> logger.info("[JS Console] {}", message)
                "warn" -> logger.info("[JS Console] {}", message)
                "error" -> logger.info("[JS Console] {}", message)
                "debug" -> logger.info("[JS Console] {}", message)
                else -> logger.info("[JS Console] [{}] {}", type, message)
            }
        }
    }

    override suspend fun getUrl(): String = apiMutex.withLock { page.url() }

    /**
     * Navigate to a new URL and wait for default load state.
     * Enhanced with Cloudflare challenge handling and anti-bot measures.
     */
    override suspend fun navigate(url: String) {
        logger.debug("Navigate to {}", url)
        apiMutex.withLock {
            try {
                val navigationTime = measureTimeMillis {
                    val response = page.navigate(url)

                    page.waitForLoadState(LoadState.LOAD)

                    // Check HTTP status code
                    response?.let {
                        val statusCode = it.status()
                        val reasonPhrase = it.statusText()
                        when (statusCode) {
                            in 400..499 -> throw HttpClientErrorException(url, statusCode, reasonPhrase)
                            in 500..599 -> throw HttpServerErrorException(url, statusCode, reasonPhrase)
                        }
                    }
                }
                logger.debug("Navigate to {} took {} ms", url, navigationTime)
            } catch (e: PlaywrightException) {
                throw mapPlaywrightException(url, e)
            }
        }
    }

    override suspend fun takeScreenshot(): IBrowserPage.Screenshot {
        logger.debug("Taking screenshot ...")
        val bytes = apiMutex.withLock {
            page.screenshot(
                Page.ScreenshotOptions().apply {
                    type = ScreenshotType.JPEG
                    timeout = 30000.0
                })
        }
        return IBrowserPage.Screenshot(bytes = bytes, mimeType = ImageMimeType.JPEG)
    }

    override suspend fun takeFullPageScreenshot(): IBrowserPage.Screenshot {
        logger.debug("Taking full-page screenshot ...")
        val bytes = apiMutex.withLock {
            page.screenshot(
                Page.ScreenshotOptions().apply {
                    type = ScreenshotType.JPEG
                    fullPage = true
                    timeout = 30000.0
                })
        }
        return IBrowserPage.Screenshot(bytes = bytes, mimeType = ImageMimeType.JPEG)
    }

    override suspend fun getFullHtml(): String {
        return apiMutex.withLock { page.content() }
    }

    override suspend fun getElementScreenshotByXPath(xpath: String): IBrowserPage.Screenshot {
        logger.debug("Taking element screenshot by XPath: {}", xpath)
        val bytes = apiMutex.withLock {
            val locator = page.locator("xpath=$xpath")
            // When XPath matches a chain (target + ancestors), select the leaf-most node
            val target = locator.last()
            target.screenshot(
                Locator.ScreenshotOptions().apply {
                    type = ScreenshotType.JPEG
                    timeout = 3000.0
                }
            )
        }
        return IBrowserPage.Screenshot(bytes = bytes, mimeType = ImageMimeType.JPEG)
    }

    override suspend fun getElementScreenshotByCssSelector(cssSelector: String): IBrowserPage.Screenshot {
        logger.debug("Taking element screenshot by CSS selector: {}", cssSelector)
        val bytes = apiMutex.withLock {
            val locator = page.locator(cssSelector)
            // When CSS selector matches multiple elements, select the last one
            val target = locator.last()
            target.screenshot(
                Locator.ScreenshotOptions().apply {
                    type = ScreenshotType.JPEG
                    timeout = 3000.0
                }
            )
        }
        return IBrowserPage.Screenshot(bytes = bytes, mimeType = ImageMimeType.JPEG)
    }

    override suspend fun getElementHtmlByXPath(xpath: String): String {
        logger.debug("Getting element outerHTML by XPath: {}", xpath)
        return apiMutex.withLock {
            val locator = page.locator("xpath=$xpath")
            val target = locator.last()
            target.evaluate("el => el.outerHTML") as String
        }
    }

    override suspend fun getElementHtmlByCssSelector(cssSelector: String): String {
        logger.debug("Getting element outerHTML by CSS selector: {}", cssSelector)
        return apiMutex.withLock {
            val locator = page.locator(cssSelector)
            val target = locator.last()
            target.evaluate("el => el.outerHTML") as String
        }
    }

    override suspend fun clickByXPathSelector(xpath: String) {
        logger.debug("Click by XPath selector: {}", xpath)
        apiMutex.withLock {
            val locator = page.locator("xpath=$xpath")
            val target = locator.last()
            target.click()
        }
    }

    override suspend fun removeElement(xpath: String) {
        logger.debug("Remove element by XPath: {}", xpath)
        val (locator, count) = apiMutex.withLock {
            val loc = page.locator("xpath=$xpath")
            val c = loc.count()
            loc to c
        }

        if (count == 0) {
            logger.warn("No element found at XPath: {}", xpath)
            return
        }

        if (count > 1) {
            logger.debug("Multiple elements ({}) found at XPath: {}, removing all", count, xpath)
        }

        // Remove all matching elements using JavaScript
        apiMutex.withLock {
            locator.evaluateAll("elements => elements.forEach(element => element.remove())")
        }

        logger.debug("Successfully removed {} element(s) at XPath: {}", count, xpath)
    }

    override suspend fun removeElementByCssSelector(cssSelector: String) {
        logger.debug("Remove element by CSS selector: {}", cssSelector)
        val (locator, count) = apiMutex.withLock {
            val loc = page.locator(cssSelector)
            val c = loc.count()
            loc to c
        }

        if (count == 0) {
            logger.warn("No element found at CSS selector: {}", cssSelector)
            return
        }

        if (count > 1) {
            logger.debug("Multiple elements ({}) found at CSS selector: {}, removing all", count, cssSelector)
        }

        // Remove all matching elements using JavaScript
        apiMutex.withLock {
            locator.evaluateAll("elements => elements.forEach(element => element.remove())")
        }

        logger.debug("Successfully removed {} element(s) at CSS selector: {}", count, cssSelector)
    }

    override suspend fun elementExists(xpath: String): Boolean {
        return apiMutex.withLock {
            val locator = page.locator("xpath=$xpath")
            val count = locator.count()
            count > 0
        }
    }

    override suspend fun elementExistsByCssSelector(cssSelector: String): Boolean {
        return apiMutex.withLock {
            val locator = page.locator(cssSelector)
            val count = locator.count()
            count > 0
        }
    }

    override suspend fun isElementVisibleByCssSelector(cssSelector: String): Boolean {
        logger.debug("Checking element visibility by CSS selector: {}", cssSelector)
        return apiMutex.withLock {
            val locator = page.locator(cssSelector)
            val target = locator.last()
            target.isVisible
        }
    }

    override suspend fun isElementVisibleByXPath(xpath: String): Boolean {
        logger.debug("Checking element visibility by XPath: {}", xpath)
        return apiMutex.withLock {
            val locator = page.locator("xpath=$xpath")
            val target = locator.last()
            target.isVisible
        }
    }

    @Serializable
    private data class IconResult(val base64: String, val xPathSelectors: List<String>)

    @Serializable
    private data class SkippedDetail(
        val tag: String,
        val classes: String,
        val beforeContent: String? = null,
        val textContent: String? = null,
        val width: Double? = null,
        val height: Double? = null,
        val error: String? = null,
        val reason: String
    )

    @Serializable
    private data class IconDebugStats(
        val totalElementsFound: Int,
        val elementsBySelector: Map<String, Int>,
        val elementsProcessed: Int,
        val skippedNoGlyph: Int,
        val skippedSvgZeroSize: Int,
        val renderingErrors: Int,
        val successfullyRendered: Int,
        val uniqueIcons: Int,
        val totalXPaths: Int,
        val skippedDetails: List<SkippedDetail>,
        val deduplicationMap: Map<String, Int>
    )

    @Serializable
    private data class IconExtractionResponse(
        val debug: IconDebugStats,
        val results: List<IconResult>
    )

    override suspend fun extractIcons(): List<IBrowserPage.Icon> {
        logger.debug("Extracting icons via evaluate()")
        val extractIconJsonRaw = apiMutex.withLock { page.evaluate(loadScript("out/extractIcons.js")) } as String

        val response = Json.decodeFromString<IconExtractionResponse>(extractIconJsonRaw)
        
        // Log debug statistics
        val debug = response.debug
        logger.debug(
            "Icon extraction stats: total={}, processed={}, rendered={}, unique={}, " +
            "skipped(no_glyph={}, svg_zero_size={}, errors={})",
            debug.totalElementsFound,
            debug.elementsProcessed,
            debug.successfullyRendered,
            debug.uniqueIcons,
            debug.skippedNoGlyph,
            debug.skippedSvgZeroSize,
            debug.renderingErrors
        )
        logger.debug("Elements by selector: {}", debug.elementsBySelector)
        logger.debug("Deduplication map: {}", debug.deduplicationMap)
        
        if (debug.skippedDetails.isNotEmpty() && logger.isTraceEnabled) {
            logger.trace("First 5 skipped details: {}", debug.skippedDetails.take(5))
        }

        val results = response.results.mapNotNull { result ->
            val cleaned = sanitizeBase64(result.base64)
            try {
                val bytes = Base64.decode(cleaned)
                IBrowserPage.Icon(
                    bytes = bytes,
                    mimeType = ImageMimeType.WEBP,
                    xPathSelectors = result.xPathSelectors
                )
            } catch (e: IllegalArgumentException) {
                logger.warn("Skipping invalid icon base64 ({} chars): {}", cleaned.length, e.message)
                null
            }
        }

        logger.debug("extractIcons produced {} unique icons", results.size)
        return results
    }

    @Serializable
    private data class ImageResult(val base64: String, val xPathSelectors: List<String>)

    @Serializable
    private data class FailedImage(val xPath: String, val reason: String)

    @Serializable
    private data class ImageExtractionResult(
        val successful: List<ImageResult>,
        val failed: List<FailedImage>
    )

    override suspend fun extractImages(): List<IBrowserPage.WebImage> {
        logger.debug("Extracting images via evaluate()")
        val extractImageJsonRaw = apiMutex.withLock { page.evaluate(loadScript("out/extractImages.js")) } as String
        val decoded = Json.decodeFromString<ImageExtractionResult>(extractImageJsonRaw)

        val results = mutableListOf<IBrowserPage.WebImage>()

        // Process successful canvas-based extractions
        decoded.successful.forEach { result ->
            val bytes = Base64.decode(result.base64)
            results.add(
                IBrowserPage.WebImage(
                    bytes = bytes,
                    mimeType = ImageMimeType.WEBP,
                    xPathSelectors = result.xPathSelectors
                )
            )
        }

        // Process failed images using screenshot fallback
        if (decoded.failed.isNotEmpty()) {
            logger.info("Processing {} failed images using screenshot fallback", decoded.failed.size)
            decoded.failed.forEach { failedImage ->
                try {
                    val isVisible = isElementVisibleByXPath(failedImage.xPath)
                    if (!isVisible) {
                        throw Exception("Skipping screenshot for invisible image at ${failedImage.xPath}")
                    }
                    val screenshot = getElementScreenshotByXPath(failedImage.xPath)
                    results.add(
                        IBrowserPage.WebImage(
                            bytes = screenshot.bytes,
                            mimeType = screenshot.mimeType,
                            xPathSelectors = listOf(failedImage.xPath)
                        )
                    )
                    logger.debug("Successfully captured screenshot for failed image at {}", failedImage.xPath)
                } catch (e: Exception) {
                    logger.warn("Failed to capture screenshot for image at {}: {}", failedImage.xPath, e.message)
                }
            }
        }

        logger.debug(
            "extractImages produced {} unique images ({} canvas-based, {} screenshot-based)",
            results.size, decoded.successful.size, decoded.failed.size
        )
        
        // Deduplicate by bytesHash to prevent constraint violations in batch upsert
        // Keep first occurrence and merge xPathSelectors
        // even though we already dedup in extractImages.ts, our screenshot fallback may introduce duplicates again
        val deduplicatedResults = results
            .groupBy { Base64.encode(it.bytesHash) }
            .map { (_, duplicates) ->
                if (duplicates.size > 1) {
                    // Merge xPathSelectors from all duplicates
                    IBrowserPage.WebImage(
                        bytes = duplicates.first().bytes,
                        mimeType = duplicates.first().mimeType,
                        xPathSelectors = duplicates.flatMap { it.xPathSelectors }.distinct()
                    )
                } else {
                    duplicates.first()
                }
            }

        logger.debug(
            "extractImages returned {} unique images (deduplicated from {} total)",
            deduplicatedResults.size, results.size
        )
        return deduplicatedResults
    }


    override suspend fun getTitle(): String {
        logger.debug("Getting title...")
        return apiMutex.withLock { page.title() }
    }

    override suspend fun getDescription(): String? {
        logger.debug("Getting description...")
        return apiMutex.withLock {
            val descriptionMeta = page.locator("meta[name=description], meta[property='og:description']")
            val description =
                if (descriptionMeta.count() > 0) descriptionMeta.first().getAttribute("content")?.trim() else null
            description
        }
    }

    override suspend fun replaceElementsByXPathWithText(replacements: List<IBrowserPage.XPathReplacementWithText>) {
        if (replacements.isEmpty()) {
            return
        }

        logger.debug("Replace {} XPath elements with text", replacements.size)

        // Convert replacements to a format that can be serialized for JavaScript
        val replacementsData = replacements.map {
            mapOf("xpath" to it.xpath, "text" to it.text)
        }

        // Perform all replacements in a single evaluate call for optimal performance
        val result = apiMutex.withLock {
            page.evaluate(
                """
            (replacements) => {
                const results = { succeeded: 0, failed: [], removed: 0 };
                
                for (const replacement of replacements) {
                    const xpath = replacement.xpath;
                    const text = replacement.text;
                    
                    // Evaluate XPath to get all matching nodes
                    const xpathResult = document.evaluate(
                        xpath,
                        document,
                        null,
                        XPathResult.ORDERED_NODE_SNAPSHOT_TYPE,
                        null
                    );
                    
                    const count = xpathResult.snapshotLength;
                    
                    if (count === 0) {
                        results.failed.push({ xpath: xpath.substring(0, 100), reason: 'no_match' });
                        continue;
                    }
                    
                    // When XPath matches multiple nodes (target + ancestors), select the leaf-most node
                    const target = xpathResult.snapshotItem(count - 1);
                    
                    if (text !== null && text !== undefined) {
                        // Replace element with text node
                        const parent = target.parentNode;
                        if (!parent || parent.nodeType === Node.DOCUMENT_NODE) {
                            results.failed.push({ xpath: xpath.substring(0, 100), reason: 'no_parent' });
                            continue;
                        }
                        const textNode = document.createTextNode(text);
                        parent.replaceChild(textNode, target);
                        results.succeeded++;
                    } else {
                        // Remove element
                        target.remove();
                        results.removed++;
                    }
                }
                
                return results;
            }
            """, replacementsData
            )
        } as Map<*, *>

        val succeeded = (result["succeeded"] as? Number)?.toInt() ?: 0
        val removed = (result["removed"] as? Number)?.toInt() ?: 0
        @Suppress("UNCHECKED_CAST")
        val failed = (result["failed"] as? List<Map<String, String>>) ?: emptyList()

        logger.debug("XPath replacements: {} succeeded, {} removed, {} failed", succeeded, removed, failed.size)
        if (failed.isNotEmpty()) {
            failed.take(5).forEach { failure ->
                logger.warn("Failed to replace XPath '{}': {}", failure["xpath"], failure["reason"])
            }
            if (failed.size > 5) {
                logger.warn("... and {} more failed replacements", failed.size - 5)
            }
        }
    }

    override suspend fun replaceElementsByCssSelectorWithText(replacements: List<IBrowserPage.CssSelectorReplacementWithText>) {
        if (replacements.isEmpty()) {
            return
        }

        logger.debug("Replace {} CSS selector elements with text", replacements.size)

        // Convert replacements to a format that can be serialized for JavaScript
        val replacementsData = replacements.map {
            mapOf("cssSelector" to it.cssSelector, "text" to it.text)
        }

        // Perform all replacements in a single evaluate call for optimal performance
        apiMutex.withLock {
            page.evaluate(
                """
            (replacements) => {
                for (const replacement of replacements) {
                    const cssSelector = replacement.cssSelector;
                    const text = replacement.text;
                    
                    // Query all matching elements
                    const elements = document.querySelectorAll(cssSelector);
                    
                    if (elements.length === 0) {
                        continue;
                    }
                    
                    // When CSS selector matches multiple elements, select the last one
                    const target = elements[elements.length - 1];
                    
                    if (text !== null && text !== undefined) {
                        // Replace element with text node
                        const parent = target.parentNode;
                        if (!parent || parent.nodeType === Node.DOCUMENT_NODE) {
                            continue;
                        }
                        const textNode = document.createTextNode(text);
                        parent.replaceChild(textNode, target);
                    } else {
                        // Remove element
                        target.remove();
                    }
                }
            }
            """, replacementsData
            )
        }
    }

    override suspend fun extractTextContent(): String {
        logger.debug("Extracting text content from page")

        return apiMutex.withLock {
            page.evaluate(
                """
            () => {
                const result = [];
                const stack = [document.body];
                
                // Elements that don't generally contain meaningful text for retrieval
                const excludeTags = new Set([
                    'script', 'style', 'noscript', 'button', 'iframe', 'nav', 'header', 'footer',
                    'aside', 'link', 'meta'
                ]);
                
                while (stack.length > 0) {
                    const node = stack.pop();
                    
                    if (node.nodeType === Node.TEXT_NODE) {
                        const text = node.textContent.trim();
                        if (text.length > 0) {
                            result.push(text);
                        }
                    } else if (node.nodeType === Node.ELEMENT_NODE) {
                        const tagName = node.tagName.toLowerCase();
                        if (!excludeTags.has(tagName)) {
                            const children = Array.from(node.childNodes);
                            for (let i = children.length - 1; i >= 0; i--) {
                                stack.push(children[i]);
                            }
                        }
                    }
                }
                
                return result.join('\n');
            }
        """
            )
        } as String
    }

    override suspend fun extractElementTextContent(elementXPath: String): String {
        logger.debug("Extracting text content from {}", elementXPath)

        return apiMutex.withLock {
            page.evaluate(
                """
            (xpath) => {
                const collectText = (root) => {
                    const result = [];
                    const stack = [root];
                    
                    // Elements that don't generally contain meaningful text for retrieval
                    const excludeTags = new Set([
                        'script', 'style', 'noscript', 'button', 'iframe', 'nav', 'header', 'footer',
                        'aside', 'link', 'meta'
                    ]);
                    
                    while (stack.length > 0) {
                        const node = stack.pop();
                        if (node.nodeType === Node.TEXT_NODE) {
                            const text = node.textContent.trim();
                            if (text.length > 0) {
                                result.push(text);
                            }
                        } else if (node.nodeType === Node.ELEMENT_NODE) {
                            const tagName = node.tagName.toLowerCase();
                            if (!excludeTags.has(tagName)) {
                                const children = Array.from(node.childNodes);
                                for (let i = children.length - 1; i >= 0; i--) {
                                    stack.push(children[i]);
                                }
                            }
                        }
                    }
                    return result;
                };

                const xpathResult = document.evaluate(
                    xpath,
                    document,
                    null,
                    XPathResult.ORDERED_NODE_SNAPSHOT_TYPE,
                    null
                );

                const count = xpathResult.snapshotLength;
                if (count === 0) {
                    return '';
                }
                const target = xpathResult.snapshotItem(count - 1);
                const result = collectText(target);
                return result.join('\n');
            }
            """,
                elementXPath
            )
        } as String
    }

    override suspend fun extractElementTextContentByCssSelector(cssSelector: String): String {
        logger.debug("Extracting text content from CSS selector: {}", cssSelector)

        return apiMutex.withLock {
            page.evaluate(
                """
            (selector) => {
                const collectText = (root) => {
                    const result = [];
                    const stack = [root];
                    
                    // Elements that don't generally contain meaningful text for retrieval
                    const excludeTags = new Set([
                        'script', 'style', 'noscript', 'button', 'iframe', 'nav', 'header', 'footer',
                        'aside', 'link', 'meta'
                    ]);
                    
                    while (stack.length > 0) {
                        const node = stack.pop();
                        if (node.nodeType === Node.TEXT_NODE) {
                            const text = node.textContent.trim();
                            if (text.length > 0) {
                                result.push(text);
                            }
                        } else if (node.nodeType === Node.ELEMENT_NODE) {
                            const tagName = node.tagName.toLowerCase();
                            if (!excludeTags.has(tagName)) {
                                const children = Array.from(node.childNodes);
                                for (let i = children.length - 1; i >= 0; i--) {
                                    stack.push(children[i]);
                                }
                            }
                        }
                    }
                    return result;
                };

                const elements = document.querySelectorAll(selector);
                if (elements.length === 0) {
                    return '';
                }
                // Use the last matched element (similar to XPath behavior)
                const target = elements[elements.length - 1];
                const result = collectText(target);
                return result.join('\n');
            }
            """,
                cssSelector
            )
        } as String
    }

    private fun loadScript(path: String): String {
        val stream = this::class.java.classLoader.getResourceAsStream(path)
            ?: throw IllegalStateException("Resource not found: $path")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun sanitizeBase64(input: String): String {
        // Trim and strip any data URL prefix like: data:image/webp;base64,....
        val trimmed = input.trim()
        return trimmed.replace(Regex("^data:[^,]+,"), "")
    }

    /**
     * Maps Playwright-specific exceptions to typed UrlProcessingException.
     * Uses Playwright error codes (net::ERR_*) for precise categorization.
     */
    private fun mapPlaywrightException(url: String, e: Exception): UrlProcessingException {
        // Check if it's a Playwright TimeoutError
        if (e.javaClass.simpleName == "TimeoutError") {
            return NetworkTimeoutException(url, e)
        }

        // Parse Playwright network error codes from exception message
        val message = e.message ?: ""
        return when {
            message.contains("net::ERR_NAME_NOT_RESOLVED") -> DnsResolutionException(url, e)
            message.contains("net::ERR_CONNECTION_REFUSED") -> ConnectionRefusedException(url, e)
            message.contains("net::ERR_SSL") || message.contains("net::ERR_CERT") -> SslHandshakeException(url, e)
            message.contains("net::ERR_TIMED_OUT") -> NetworkTimeoutException(url, e)
            message.contains("net::ERR_TOO_MANY_REDIRECTS") -> RedirectLoopException(url, e)
            else -> BrowserNavigationException(url, e)
        }
    }
}