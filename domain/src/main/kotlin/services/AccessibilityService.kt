package io.deepsearch.domain.services

import com.deque.html.axecore.playwright.AxeBuilder
import com.deque.html.axecore.results.AxeResults
import com.deque.html.axecore.results.Rule
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import io.ktor.http.Url
import io.deepsearch.domain.exceptions.WebScrapingException
import io.deepsearch.domain.models.valueobjects.AccessibilityElement
import io.deepsearch.domain.models.valueobjects.AccessibilityScanResult
import io.deepsearch.domain.models.valueobjects.AccessibilityViolationType

/**
 * Domain service for accessibility testing and analysis
 */
class AccessibilityService {

    /**
     * Scans a website and returns accessibility elements (violations, passes, and incomplete results)
     * 
     * @param url The URL to scan for accessibility issues
     * @return AccessibilityScanResult containing all identified accessibility elements
     * @throws WebScrapingException if the website cannot be accessed or scanned
     */
    suspend fun getAccessibilityElements(url: Url): AccessibilityScanResult {
        return try {
            val playwright = Playwright.create()
            val browser = playwright.chromium().launch(
                BrowserType.LaunchOptions().setHeadless(true)
            )
            
            val page = browser.newPage()
            
            // Configure page settings for better accessibility scanning
            page.setDefaultTimeout(30000.0) // 30 seconds timeout
            
            // Navigate to the URL
            page.navigate(url.toString())
            
            // Wait for the page to load completely
            page.waitForLoadState()
            
            // Run accessibility scan using axe-core
            val axeResults = AxeBuilder(page)
                .withTags(listOf("wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "best-practice"))
                .analyze()
            
            // Convert results to our domain model
            val scanResult = mapAxeResultsToScanResult(url.toString(), axeResults)
            
            // Clean up resources
            page.close()
            browser.close()
            playwright.close()
            
            scanResult
            
        } catch (exception: Exception) {
            throw WebScrapingException("Failed to scan website accessibility: ${url}", exception)
        }
    }

    /**
     * Scans a specific part of a website for accessibility elements
     * 
     * @param url The URL to scan
     * @param cssSelector CSS selector to focus the scan on a specific element
     * @return AccessibilityScanResult containing accessibility elements for the specified area
     */
    suspend fun getAccessibilityElementsForElement(url: Url, cssSelector: String): AccessibilityScanResult {
        return try {
            val playwright = Playwright.create()
            val browser = playwright.chromium().launch(
                BrowserType.LaunchOptions().setHeadless(true)
            )
            
            val page = browser.newPage()
            page.setDefaultTimeout(30000.0)
            page.navigate(url.toString())
            page.waitForLoadState()
            
            // Wait for the specific element to be present
            page.locator(cssSelector).waitFor()
            
            // Run accessibility scan focused on the specific element
            val axeResults = AxeBuilder(page)
                .include(listOf(cssSelector))
                .withTags(listOf("wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "best-practice"))
                .analyze()
            
            val scanResult = mapAxeResultsToScanResult(url.toString(), axeResults)
            
            // Clean up resources
            page.close()
            browser.close()
            playwright.close()
            
            scanResult
            
        } catch (exception: Exception) {
            throw WebScrapingException("Failed to scan element accessibility: ${url} - ${cssSelector}", exception)
        }
    }

    /**
     * Maps AxeResults to our domain model
     */
    private fun mapAxeResultsToScanResult(url: String, axeResults: AxeResults): AccessibilityScanResult {
        val violations = axeResults.violations?.map { rule ->
            mapRuleToAccessibilityElements(rule, AccessibilityViolationType.VIOLATION)
        }?.flatten() ?: emptyList()

        val passes = axeResults.passes?.map { rule ->
            mapRuleToAccessibilityElements(rule, AccessibilityViolationType.PASS)
        }?.flatten() ?: emptyList()

        val incomplete = axeResults.incomplete?.map { rule ->
            mapRuleToAccessibilityElements(rule, AccessibilityViolationType.INCOMPLETE)
        }?.flatten() ?: emptyList()

        return AccessibilityScanResult(
            url = url,
            violations = violations,
            passes = passes,
            incomplete = incomplete
        )
    }

    /**
     * Maps a single axe Rule to a list of AccessibilityElement objects
     */
    private fun mapRuleToAccessibilityElements(rule: Rule, violationType: AccessibilityViolationType): List<AccessibilityElement> {
        return rule.nodes?.map { node ->
            AccessibilityElement(
                ruleId = rule.id ?: "",
                impact = rule.impact ?: "",
                description = rule.description ?: "",
                helpUrl = rule.helpUrl ?: "",
                tags = rule.tags?.toList() ?: emptyList(),
                target = node.target?.toString() ?: "",
                html = node.html ?: "",
                violationType = violationType
            )
        } ?: emptyList()
    }
} 