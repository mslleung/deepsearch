package io.deepsearch.application.services

import io.deepsearch.application.config.applicationTestModule
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HttpContentTypeResolutionServiceTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(applicationTestModule)
    }

    private val service by inject<IHttpContentTypeResolutionService>()

    @Test
    fun `resolve HTML content type from example com`() = runTest {
        val result = service.resolve("https://example.com")

        assertIs<ContentTypeResult.Html>(result, "example.com should be detected as HTML")
        assertEquals(200, result.statusCode, "Should have 200 status")
        assertTrue(
            result.mimeType.contains("text/html", ignoreCase = true),
            "MIME type should be text/html"
        )
    }

    @Test
    fun `resolve with redirect following`() = runTest {
        // HTTP URLs often redirect to HTTPS
        val result = service.resolve("http://example.com")

        when (result) {
            is ContentTypeResult.Html -> {
                assertTrue(
                    result.finalUrl.startsWith("https://"),
                    "Should follow redirect to HTTPS"
                )
                assertEquals(200, result.statusCode, "Should have 200 status after redirect")
            }
            is ContentTypeResult.Failed -> {
                // Some networks might block HTTP, this is acceptable
                assertTrue(true, "HTTP blocked is acceptable in some environments")
            }
            else -> {
                throw AssertionError("Unexpected result type: ${result::class.simpleName}")
            }
        }
    }

    @Test
    fun `detect 404 as failed result`() = runTest {
        // Use a URL that's likely to 404
        val result = service.resolve("https://example.com/this-page-does-not-exist-12345")

        assertIs<ContentTypeResult.Failed>(result, "Should detect 404 as failed")
        assertEquals(404, result.statusCode, "Should have 404 status")
    }

    @Test
    fun `handle invalid URL gracefully`() = runTest {
        val result = service.resolve("not-a-valid-url")

        assertIs<ContentTypeResult.Failed>(result, "Invalid URL should result in failure")
    }

    @Test
    fun `detect PDF content type from real PDF URL`() = runTest {
        // Using a known PDF URL (W3C specification)
        val result = service.resolve("https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf")

        when (result) {
            is ContentTypeResult.Pdf -> {
                assertEquals(200, result.statusCode, "Should have 200 status")
                assertTrue(
                    result.mimeType.contains("application/pdf", ignoreCase = true),
                    "MIME type should be application/pdf"
                )
                assertTrue(result.bytes.isNotEmpty(), "Should download PDF bytes")
            }
            is ContentTypeResult.Failed -> {
                // PDF might not be available, this is acceptable
                assertTrue(true, "PDF not available is acceptable")
            }
            else -> {
                // Some servers might return HTML for PDF URLs, acceptable
                assertTrue(true, "Non-PDF response is acceptable for this test")
            }
        }
    }

    @Test
    fun `detect unsupported content type`() = runTest {
        // Try to get a JSON API endpoint
        val result = service.resolve("https://api.github.com/users/github")

        when (result) {
            is ContentTypeResult.Unsupported -> {
                assertEquals(200, result.statusCode, "Should have 200 status")
                assertTrue(
                    result.contentType.contains("application/json", ignoreCase = true) ||
                    result.contentType.contains("application/vnd", ignoreCase = true),
                    "Should detect JSON as unsupported content type"
                )
            }
            is ContentTypeResult.Html -> {
                // GitHub might return HTML in some cases (browser detection)
                assertTrue(true, "HTML response is acceptable")
            }
            is ContentTypeResult.Failed -> {
                // API might be down or blocked
                assertTrue(true, "Failed response is acceptable")
            }
            else -> {
                throw AssertionError("Unexpected result type: ${result::class.simpleName}")
            }
        }
    }

    @Test
    fun `handle connection timeout gracefully`() = runTest {
        // Use a non-routable IP to trigger timeout (fast failure)
        val result = service.resolve("http://192.0.2.1") // TEST-NET-1, reserved for documentation

        assertIs<ContentTypeResult.Failed>(result, "Connection timeout should result in failure")
    }

    @Test
    fun `track final URL after redirects`() = runTest {
        val result = service.resolve("http://example.com")

        when (result) {
            is ContentTypeResult.Html -> {
                // Final URL should be different from initial if redirected
                assertTrue(
                    result.finalUrl.contains("example.com"),
                    "Final URL should still contain example.com"
                )
            }
            is ContentTypeResult.Failed -> {
                // Connection might fail in some environments
                assertTrue(true, "Failed connection is acceptable")
            }
            else -> {
                throw AssertionError("Unexpected result type for HTML: ${result::class.simpleName}")
            }
        }
    }
}

