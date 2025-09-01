package io.deepsearch.domain.browser.playwright

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.ScreenshotType
import com.microsoft.playwright.options.LoadState
import io.deepsearch.domain.browser.IBrowserPage
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Playwright-backed implementation of a browser page.
 *
 * Uses Playwright locators and ARIA roles to build a human-oriented snapshot of the page,
 * keeping ARIA and DOM details internal to this adapter.
 */
class PlaywrightBrowserPage(
    private val page: Page
) : IBrowserPage {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Navigate to a new URL and wait for default load state.
     */
    override fun navigate(url: String) {
        logger.debug("Navigate to {}", url)
        page.navigate(url)
    }

    override fun takeScreenshot(): IBrowserPage.Screenshot {
        logger.debug("Taking screenshot ...")
        val bytes = page.screenshot(
            Page.ScreenshotOptions().apply {
                type = ScreenshotType.JPEG
            })
        return IBrowserPage.Screenshot(bytes = bytes, mimeType = IBrowserPage.ImageMimeType.JPEG)
    }

    override fun takeFullPageScreenshot(): IBrowserPage.Screenshot {
        logger.debug("Taking full-page screenshot ...")
        val bytes = page.screenshot(
            Page.ScreenshotOptions().apply {
                type = ScreenshotType.JPEG
                fullPage = true
            })
        return IBrowserPage.Screenshot(bytes = bytes, mimeType = IBrowserPage.ImageMimeType.JPEG)
    }

    /**
     * Build a comprehensive, human-facing snapshot of the current page.
     */
    override fun getPageInformation(): IBrowserPage.PageInformation {
        logger.debug("Building PageInformation (pre-url) ...")
        val url = page.url()
        val title = page.title()
        val description: String? = findMetaContent("description")
        val language: String? = getAttributeOrNull(page.locator("html").first(), "lang")
        val canonicalUrl: String? = getAttributeOrNull(page.locator("head link[rel=canonical]").first(), "href")

        val headings = collectHeadings()
        val mainText = collectMainText()
        val images = collectImages()
        val breadcrumbs = collectBreadcrumbs()

        val actionSpace = collectActionSpace()

        logger.debug(
            "Built PageInformation for {} | title='{}' desc?={} lang='{}' canon?={} headings={} images={} breadcrumbs={} links={} buttons={} inputs={} forms={}",
            url,
            title,
            description != null,
            language,
            canonicalUrl != null,
            headings.size,
            images.size,
            breadcrumbs.size,
            actionSpace.links.size,
            actionSpace.buttons.size,
            actionSpace.inputs.size,
            actionSpace.forms.size
        )

        return IBrowserPage.PageInformation(
            url = url,
            title = title,
            description = description,
            language = language,
            canonicalUrl = canonicalUrl,
            headings = headings,
            mainText = mainText,
            images = images,
            breadcrumbs = breadcrumbs,
            actionSpace = actionSpace
        )
    }

    // --- Interactions (V1) ---

    override fun clickLinkByHref(href: String): Boolean {
        return try {
            val base = try { java.net.URI(page.url()) } catch (_: Throwable) { null }
            val absolute = try { base?.resolve(href)?.toString() ?: href } catch (_: Throwable) { href }
            val loc = page.locator("a[href='$absolute'], a[href='${href}']")
            if (loc.count() > 0) {
                loc.first().click()
                true
            } else {
                // fallback: contains match
                val anchors = page.locator("a[href]")
                val n = anchors.count()
                var i = 0
                while (i < n) {
                    val el = anchors.nth(i)
                    val v = el.getAttribute("href") ?: ""
                    if (v.contains(href) || v.contains(absolute)) {
                        el.click()
                        return true
                    }
                    i++
                }
                false
            }
        } catch (t: Throwable) {
            logger.debug("clickLinkByHref failed: {}", t.message)
            false
        }
    }

    override fun clickByText(text: String): Boolean {
        return try {
            // Prefer role-based locators
            val byRoleLink = page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName(text))
            if (byRoleLink.count() > 0) { byRoleLink.first().click(); return true }
            val byRoleButton = page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(text))
            if (byRoleButton.count() > 0) { byRoleButton.first().click(); return true }

            // Fallback: text matching
            val cssCandidates = listOf(
                "a:has-text('$text')",
                "button:has-text('$text')",
                "[role=button]:has-text('$text')"
            )
            for (sel in cssCandidates) {
                val loc = page.locator(sel)
                if (loc.count() > 0) { loc.first().click(); return true }
            }

            // Last resort: query all clickable elements and filter by innerText contains
            val clickable = page.locator("a, button, [role=button]")
            val n = clickable.count()
            var i = 0
            while (i < n) {
                val el = clickable.nth(i)
                val t = try { el.innerText() } catch (_: Throwable) { null }
                if (t != null && t.contains(text, ignoreCase = true)) {
                    el.click()
                    return true
                }
                i++
            }
            false
        } catch (t: Throwable) {
            logger.debug("clickByText failed: {}", t.message)
            false
        }
    }

    override fun clickAt(x: Double, y: Double): Boolean {
        return try {
            page.mouse().click(x, y)
            true
        } catch (t: Throwable) {
            logger.debug("clickAt failed: {}", t.message)
            false
        }
    }

    override fun fillInputByNameOrId(nameOrId: String, value: String): Boolean {
        return try {
            val selectors = listOf(
                "input[name='${nameOrId}']",
                "textarea[name='${nameOrId}']",
                "select[name='${nameOrId}']",
                "#${nameOrId}"
            )
            for (sel in selectors) {
                val loc = page.locator(sel)
                if (loc.count() > 0) {
                    val el = loc.first()
                    val tag = try { el.evaluate("e => e.tagName.toLowerCase()") as String } catch (_: Throwable) { "input" }
                    when (tag) {
                        "select" -> el.selectOption(value)
                        else -> el.fill(value)
                    }
                    return true
                }
            }
            false
        } catch (t: Throwable) {
            logger.debug("fillInputByNameOrId failed: {}", t.message)
            false
        }
    }

    override fun submitFormByAction(actionHref: String?): Boolean {
        return try {
            val form = if (!actionHref.isNullOrBlank()) {
                page.locator("form[action='${actionHref}']").first()
            } else {
                page.locator("form").first()
            }
            form.evaluate("f => f.requestSubmit ? f.requestSubmit() : f.submit()")
            true
        } catch (t: Throwable) {
            logger.debug("submitFormByAction failed: {}", t.message)
            false
        }
    }

    override fun waitForNavigationOrIdle(timeoutMs: Long): Boolean {
        return try {
            val end = System.currentTimeMillis() + timeoutMs
            // Try to detect navigation; otherwise wait for network idle
            page.waitForLoadState()
            // Additional settle time up to timeout
            val remaining = end - System.currentTimeMillis()
            if (remaining > 0) {
                // Playwright Java API does not support per-call timeout for this overload consistently across versions
                // so we do a best-effort second wait for NETWORKIDLE.
                page.waitForLoadState(LoadState.NETWORKIDLE)
            }
            true
        } catch (t: Throwable) {
            logger.debug("waitForNavigationOrIdle timeout or failure: {}", t.message)
            false
        }
    }

    override fun scrollToY(y: Double) {
        try {
            page.evaluate("window.scrollTo(0, arguments[0])", y)
        } catch (t: Throwable) {
            logger.debug("scrollToY failed: {}", t.message)
        }
    }

    override fun getViewportSize(): IBrowserPage.ViewportSize {
        val vs = page.viewportSize()
        return IBrowserPage.ViewportSize(width = vs?.width ?: 0, height = vs?.height ?: 0)
    }

    /**
     * Find a meta content by name or OpenGraph variant (og:name).
     * Returns null when not present.
     */
    private fun findMetaContent(name: String): String? {
        return try {
            val contentByName = getAttributeOrNull(page.locator("meta[name=$name]").first(), "content")
            if (contentByName != null) return contentByName
            getAttributeOrNull(page.locator("meta[name=og:$name], meta[property=og:$name]").first(), "content")
        } catch (t: Throwable) {
            logger.debug("Meta {} not found: {}", name, t.message)
            null
        }
    }

    /**
     * Safely read attribute value from a locator, returning null on timeout or absence.
     */
    private fun getAttributeOrNull(locator: com.microsoft.playwright.Locator, name: String): String? {
        return try {
            locator.getAttribute(name)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Collect headings H1..H6 with levels in document order.
     */
    private fun collectHeadings(): List<IBrowserPage.Heading> {
        val result = mutableListOf<IBrowserPage.Heading>()
        val tagToLevel = mapOf(
            "h1" to IBrowserPage.HeadingLevel.H1,
            "h2" to IBrowserPage.HeadingLevel.H2,
            "h3" to IBrowserPage.HeadingLevel.H3,
            "h4" to IBrowserPage.HeadingLevel.H4,
            "h5" to IBrowserPage.HeadingLevel.H5,
            "h6" to IBrowserPage.HeadingLevel.H6
        )
        for ((tag, level) in tagToLevel) {
            val loc = page.locator(tag)
            val count = loc.count()
            var i = 0
            while (i < count) {
                val text = loc.nth(i).innerText().trim()
                if (text.isNotEmpty()) {
                    result.add(IBrowserPage.Heading(level = level, text = text))
                }
                i++
            }
        }
        return result
    }

    /**
     * Best-effort extraction of the main content text using semantic regions
     * (main/article/[role=main]). Returns null if not found or empty.
     */
    private fun collectMainText(): String? {
        val regions = page.locator("main, article, [role=main]")
        val count = try {
            regions.count()
        } catch (_: Throwable) {
            0
        }
        if (count <= 0) return null
        val first = regions.first()
        return try {
            val text = first.innerText().trim()
            if (text.isNotEmpty()) text else null
        } catch (t: Throwable) {
            logger.debug("No main/article region text found: {}", t.message)
            null
        }
    }

    /**
     * Visible images with their alternative text if present.
     */
    private fun collectImages(): List<IBrowserPage.Image> {
        val images = mutableListOf<IBrowserPage.Image>()
        val loc = page.locator("img[src]")
        val count = loc.count()
        var i = 0
        while (i < count) {
            val el = loc.nth(i)
            val src = el.getAttribute("src")
            if (src != null) {
                val alt = el.getAttribute("alt")
                images.add(IBrowserPage.Image(alt = alt, src = src))
            }
            i++
        }
        return images
    }

    /**
     * Breadcrumb labels if a breadcrumb navigation is detected via common
     * semantics (ARIA, common CSS hooks, or schema.org). Returns an ordered list
     * of labels, or an empty list if not found.
     */
    private fun collectBreadcrumbs(): List<String> {
        // Prefer nav[aria-label=breadcrumb], then .breadcrumb, then schema.org breadcrumbs
        val candidates = listOf(
            "nav[aria-label=breadcrumb]",
            "[role=navigation][aria-label=breadcrumb]",
            "ol.breadcrumb, nav .breadcrumb, .breadcrumbs, nav[aria-label=breadcrumbs]",
            "nav[aria-label=Breadcrumb]",
            "[itemtype='https://schema.org/BreadcrumbList']"
        )
        for (selector in candidates) {
            val container = page.locator(selector)
            if (container.count() > 0) {
                val text = try {
                    container.first().innerText().trim()
                } catch (t: Throwable) {
                    logger.debug("Breadcrumbs extraction failed for selector {}: {}", selector, t.message); null
                }
                if (!text.isNullOrEmpty()) {
                    return text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                }
            }
        }
        return emptyList()
    }

    /**
     * Build the high-level action space: links, buttons, inputs, and forms.
     */
    private fun collectActionSpace(): IBrowserPage.ActionSpace {
        val links = collectLinks()
        val buttons = collectButtons()
        val inputs = collectInputs()
        val forms = collectForms()
        return IBrowserPage.ActionSpace(
            links = links,
            buttons = buttons,
            inputs = inputs,
            forms = forms
        )
    }

    /**
     * Collect anchor links with their visible text labels.
     */
    private fun collectLinks(): List<IBrowserPage.NavigationLink> {
        val result = mutableListOf<IBrowserPage.NavigationLink>()
        val anchor = page.locator("a[href]")
        val count = anchor.count()
        val base = try {
            java.net.URI(page.url())
        } catch (_: Throwable) {
            null
        }
        var i = 0
        while (i < count) {
            val el = anchor.nth(i)
            val href = el.getAttribute("href")
            if (!href.isNullOrBlank()) {
                val text = try {
                    el.innerText().trim().ifEmpty { null }
                } catch (t: Throwable) {
                    logger.debug("Link text extraction failed: {}", t.message); null
                }
                val absoluteHref = try {
                    base?.resolve(href)?.toString() ?: href
                } catch (_: Throwable) {
                    href
                }
                result.add(IBrowserPage.NavigationLink(text = text, href = absoluteHref))
            }
            i++
        }
        logger.debug("Collected {} links", result.size)
        return result
    }

    /**
     * Collect buttons from semantic elements and ARIA role.
     * Categorize into button/submit/reset using attributes when possible.
     */
    private fun collectButtons(): List<IBrowserPage.Button> {
        val result = mutableListOf<IBrowserPage.Button>()

        fun append(locSelector: String, type: IBrowserPage.ButtonType) {
            val loc = page.locator(locSelector)
            val count = loc.count()
            var i = 0
            while (i < count) {
                val el = loc.nth(i)
                val text = try {
                    el.innerText().trim().ifEmpty { null }
                } catch (_: Throwable) {
                    null
                }
                val name = el.getAttribute("name")
                val id = el.getAttribute("id")
                result.add(IBrowserPage.Button(type = type, text = text, name = name, id = id))
                i++
            }
        }

        append("button:not([type]), button[type=button]", IBrowserPage.ButtonType.BUTTON)
        append("button[type=submit], input[type=submit]", IBrowserPage.ButtonType.SUBMIT)
        append("button[type=reset], input[type=reset]", IBrowserPage.ButtonType.RESET)

        // ARIA role=button
        val ariaButtons = page.getByRole(AriaRole.BUTTON)
        val ariaCount = ariaButtons.count()
        var i = 0
        while (i < ariaCount) {
            val el = ariaButtons.nth(i)
            val text = try {
                el.innerText().trim().ifEmpty { null }
            } catch (t: Throwable) {
                logger.debug("ARIA button text extraction failed: {}", t.message); null
            }
            val name = el.getAttribute("name")
            val id = el.getAttribute("id")
            result.add(IBrowserPage.Button(type = IBrowserPage.ButtonType.BUTTON, text = text, name = name, id = id))
            i++
        }

        logger.debug("Collected {} buttons", result.size)
        return result
    }

    /**
     * Collect inputs from native elements and ARIA roles, mapping to a fixed taxonomy.
     */
    private fun collectInputs(): List<IBrowserPage.InputField> {
        val result = mutableListOf<IBrowserPage.InputField>()

        fun toType(t: String?): IBrowserPage.InputType {
            return when ((t ?: "").lowercase()) {
                "text" -> IBrowserPage.InputType.TEXT
                "password" -> IBrowserPage.InputType.PASSWORD
                "email" -> IBrowserPage.InputType.EMAIL
                "search" -> IBrowserPage.InputType.SEARCH
                "url" -> IBrowserPage.InputType.URL
                "number" -> IBrowserPage.InputType.NUMBER
                "checkbox" -> IBrowserPage.InputType.CHECKBOX
                "radio" -> IBrowserPage.InputType.RADIO
                "file" -> IBrowserPage.InputType.FILE
                "date" -> IBrowserPage.InputType.DATE
                "time" -> IBrowserPage.InputType.TIME
                "datetime-local" -> IBrowserPage.InputType.DATETIME_LOCAL
                "month" -> IBrowserPage.InputType.MONTH
                "week" -> IBrowserPage.InputType.WEEK
                "tel" -> IBrowserPage.InputType.TEL
                "color" -> IBrowserPage.InputType.COLOR
                "range" -> IBrowserPage.InputType.RANGE
                "hidden" -> IBrowserPage.InputType.HIDDEN
                else -> IBrowserPage.InputType.UNKNOWN
            }
        }

        fun appendInputs(selector: String, explicitType: IBrowserPage.InputType? = null) {
            val loc = page.locator(selector)
            val count = loc.count()
            var i = 0
            while (i < count) {
                val el = loc.nth(i)
                val t = explicitType ?: toType(el.getAttribute("type"))
                val name = el.getAttribute("name")
                val id = el.getAttribute("id")
                val placeholder = el.getAttribute("placeholder")
                result.add(IBrowserPage.InputField(type = t, name = name, id = id, placeholder = placeholder))
                i++
            }
        }

        appendInputs("input")
        appendInputs("textarea", IBrowserPage.InputType.TEXTAREA)
        appendInputs("select", IBrowserPage.InputType.SELECT)

        // ARIA role-based textboxes, comboboxes, spinbuttons
        appendAriaInputLike(result)

        logger.debug("Collected {} inputs", result.size)
        return result
    }

    /**
     * Enrich input detection with ARIA roles mapped to our input taxonomy.
     */
    private fun appendAriaInputLike(target: MutableList<IBrowserPage.InputField>) {
        fun addFromRole(role: AriaRole, type: IBrowserPage.InputType) {
            val loc = page.getByRole(role)
            val count = loc.count()
            var i = 0
            while (i < count) {
                val el = loc.nth(i)
                val name = el.getAttribute("name")
                val id = el.getAttribute("id")
                val placeholder = el.getAttribute("placeholder")
                target.add(IBrowserPage.InputField(type = type, name = name, id = id, placeholder = placeholder))
                i++
            }
        }
        addFromRole(AriaRole.TEXTBOX, IBrowserPage.InputType.TEXT)
        addFromRole(AriaRole.COMBOBOX, IBrowserPage.InputType.SELECT)
        addFromRole(AriaRole.SPINBUTTON, IBrowserPage.InputType.NUMBER)
        addFromRole(AriaRole.SEARCHBOX, IBrowserPage.InputType.SEARCH)
    }

    /**
     * Collect forms with declared method/action and contained input names.
     */
    private fun collectForms(): List<IBrowserPage.Form> {
        val result = mutableListOf<IBrowserPage.Form>()
        val loc = page.locator("form")
        val count = loc.count()
        var i = 0
        while (i < count) {
            val form = loc.nth(i)
            val method = when ((form.getAttribute("method") ?: "GET").uppercase()) {
                "GET" -> IBrowserPage.HttpMethod.GET
                "POST" -> IBrowserPage.HttpMethod.POST
                "PUT" -> IBrowserPage.HttpMethod.PUT
                "PATCH" -> IBrowserPage.HttpMethod.PATCH
                "DELETE" -> IBrowserPage.HttpMethod.DELETE
                "OPTIONS" -> IBrowserPage.HttpMethod.OPTIONS
                "HEAD" -> IBrowserPage.HttpMethod.HEAD
                else -> IBrowserPage.HttpMethod.UNKNOWN
            }
            val action = form.getAttribute("action")
            val inputs = form.locator("input, textarea, select").all().mapNotNull { it.getAttribute("name") }
            result.add(IBrowserPage.Form(method = method, action = action, inputNames = inputs))
            i++
        }
        logger.debug("Collected {} forms", result.size)
        return result
    }
}