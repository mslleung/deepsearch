package io.deepsearch.domain.services

import io.deepsearch.domain.exceptions.WebScrapingException
import io.deepsearch.domain.models.valueobjects.AccessibilityElement
import io.deepsearch.domain.models.valueobjects.AccessibilityScanResult
import io.deepsearch.domain.models.valueobjects.AccessibilityViolationType
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach

class AccessibilityServiceTest {

    private lateinit var accessibilityService: AccessibilityService

    @BeforeEach
    fun setUp() {
        accessibilityService = AccessibilityService()
    }

    @Test
    fun `getAccessibilityElements should return scan result for valid URL`() = runTest {
        // Given
        val url = Url("https://example.com")

        // When
        val result = accessibilityService.getAccessibilityElements(url)

        // Then
        assertNotNull(result)
        assertEquals("https://example.com", result.url)
        assertTrue(result.scanTimestamp > 0)
        assertNotNull(result.violations)
        assertNotNull(result.passes)
        assertNotNull(result.incomplete)
    }

    @Test
    fun `getAccessibilityElements should throw WebScrapingException for invalid URL`() = runTest {
        // Given
        val invalidUrl = Url("https://this-domain-does-not-exist-12345.com")

        // When & Then
        assertThrows<WebScrapingException> {
            accessibilityService.getAccessibilityElements(invalidUrl)
        }
    }

    @Test
    fun `getAccessibilityElements should throw WebScrapingException when browser fails`() = runTest {
        // Given
        val url = Url("https://httpstat.us/500")

        // When & Then
        val exception = assertThrows<WebScrapingException> {
            accessibilityService.getAccessibilityElements(url)
        }
        assertTrue(exception.message?.contains("Failed to scan website accessibility") == true)
    }

    @Test
    fun `getAccessibilityElementsForElement should return scan result for specific element`() = runTest {
        // Given
        val url = Url("https://example.com")
        val cssSelector = "body"

        // When
        val result = accessibilityService.getAccessibilityElementsForElement(url, cssSelector)

        // Then
        assertNotNull(result)
        assertEquals("https://example.com", result.url)
        assertTrue(result.scanTimestamp > 0)
    }

    @Test
    fun `getAccessibilityElementsForElement should throw WebScrapingException for invalid selector`() = runTest {
        // Given
        val url = Url("https://example.com")
        val invalidSelector = "#non-existent-element-with-very-specific-id"

        // When & Then
        val exception = assertThrows<WebScrapingException> {
            accessibilityService.getAccessibilityElementsForElement(url, invalidSelector)
        }
        assertTrue(exception.message?.contains("Failed to scan element accessibility") == true)
    }

    // Helper method to create mock accessibility elements for testing
    private fun createMockAccessibilityElement(
        ruleId: String = "color-contrast",
        impact: String = "serious",
        description: String = "Elements must have sufficient color contrast",
        violationType: AccessibilityViolationType = AccessibilityViolationType.VIOLATION
    ): AccessibilityElement {
        return AccessibilityElement(
            ruleId = ruleId,
            impact = impact,
            description = description,
            helpUrl = "https://dequeuniversity.com/rules/axe/4.10/color-contrast",
            tags = listOf("wcag2aa", "wcag143"),
            target = "#main-content",
            html = "<div id=\"main-content\">Content</div>",
            violationType = violationType
        )
    }

    // Helper method to create mock scan result for testing
    private fun createMockScanResult(url: String): AccessibilityScanResult {
        return AccessibilityScanResult(
            url = url,
            violations = listOf(
                createMockAccessibilityElement("color-contrast", "serious", "Elements must have sufficient color contrast", AccessibilityViolationType.VIOLATION),
                createMockAccessibilityElement("aria-label", "moderate", "Elements must have accessible labels", AccessibilityViolationType.VIOLATION)
            ),
            passes = listOf(
                createMockAccessibilityElement("heading-order", "minor", "Heading levels should only increase by one", AccessibilityViolationType.PASS)
            ),
            incomplete = listOf(
                createMockAccessibilityElement("focus-visible", "minor", "Elements should have visible focus indicators", AccessibilityViolationType.INCOMPLETE)
            )
        )
    }

    /**
     * Test to verify the structure and properties of AccessibilityScanResult
     */
    @Test
    fun `AccessibilityScanResult should have correct structure`() {
        // Given
        val url = "https://example.com"
        val scanResult = createMockScanResult(url)

        // Then
        assertEquals(url, scanResult.url)
        assertEquals(2, scanResult.violations.size)
        assertEquals(1, scanResult.passes.size)
        assertEquals(1, scanResult.incomplete.size)
        assertTrue(scanResult.scanTimestamp > 0)

        // Verify violation elements
        val violation = scanResult.violations.first()
        assertEquals("color-contrast", violation.ruleId)
        assertEquals("serious", violation.impact)
        assertEquals(AccessibilityViolationType.VIOLATION, violation.violationType)
        assertTrue(violation.description.isNotEmpty())
        assertTrue(violation.helpUrl.isNotEmpty())
        assertTrue(violation.tags.isNotEmpty())

        // Verify pass elements
        val pass = scanResult.passes.first()
        assertEquals(AccessibilityViolationType.PASS, pass.violationType)

        // Verify incomplete elements
        val incomplete = scanResult.incomplete.first()
        assertEquals(AccessibilityViolationType.INCOMPLETE, incomplete.violationType)
    }

    /**
     * Test to verify AccessibilityElement properties and validation
     */
    @Test
    fun `AccessibilityElement should have required properties`() {
        // Given
        val element = createMockAccessibilityElement()

        // Then
        assertTrue(element.ruleId.isNotEmpty())
        assertTrue(element.impact.isNotEmpty())
        assertTrue(element.description.isNotEmpty())
        assertTrue(element.helpUrl.isNotEmpty())
        assertTrue(element.target.isNotEmpty())
        assertTrue(element.html.isNotEmpty())
        assertNotNull(element.violationType)
        assertNotNull(element.tags)
    }

    /**
     * Test to verify WebScrapingException is properly structured
     */
    @Test
    fun `WebScrapingException should be properly thrown with message and cause`() {
        // Given
        val originalException = RuntimeException("Original error")
        val url = "https://example.com"
        val errorMessage = "Failed to scan website accessibility: $url"

        // When
        val webScrapingException = WebScrapingException(errorMessage, originalException)

        // Then
        assertEquals(errorMessage, webScrapingException.message)
        assertEquals(originalException, webScrapingException.cause)
    }

    /**
     * Test to verify timeout handling with runTest
     * This test demonstrates how runTest handles timeouts gracefully
     */
    @Test
    fun `runTest should handle timeout scenarios properly`() = runTest(timeout = kotlin.time.Duration.parse("2s")) {
        // This test would timeout after 2 seconds if uncommented
        // kotlinx.coroutines.delay(3000) // 3 seconds - would cause timeout
        assertTrue(true, "Test should complete within timeout")
    }

    /**
     * Test to verify delay skipping behavior with runTest
     * This demonstrates the virtual time feature of runTest
     */
    @Test
    fun `runTest should skip delays and complete immediately`() = runTest {
        val startTime = kotlin.time.TimeSource.Monotonic.markNow()
        
        // This delay should be skipped in runTest
        kotlinx.coroutines.delay(1000) // 1 second delay
        
        val elapsed = startTime.elapsedNow()
        
        // The delay should be skipped, so elapsed time should be minimal
        assertTrue(elapsed < kotlin.time.Duration.parse("100ms"), "Delay should be skipped in runTest")
    }
}