package io.deepsearch.application.services

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested

class NormalizeUrlServiceTest {

    private val service = NormalizeUrlService()
    private val defaultConfig = UrlNormalizationConfig()

    @Nested
    inner class BasicNormalization {

        @Test
        fun `should lowercase scheme and host`() {
            val result = service.normalize("HTTPS://WWW.EXAMPLE.COM/Path")
            assertEquals("https://www.example.com/Path", result)
        }

        @Test
        fun `should remove default port 80 for http`() {
            val result = service.normalize("http://example.com:80/path")
            assertEquals("http://example.com/path", result)
        }

        @Test
        fun `should remove default port 443 for https`() {
            val result = service.normalize("https://example.com:443/path")
            assertEquals("https://example.com/path", result)
        }

        @Test
        fun `should keep non-default ports`() {
            val result = service.normalize("https://example.com:8080/path")
            assertEquals("https://example.com:8080/path", result)
        }

        @Test
        fun `should remove fragment`() {
            val result = service.normalize("https://example.com/path#section")
            assertEquals("https://example.com/path", result)
        }

        @Test
        fun `should add root path if missing`() {
            val result = service.normalize("https://example.com")
            assertEquals("https://example.com/", result)
        }
    }

    @Nested
    inner class PathNormalization {

        @Test
        fun `should remove trailing slash from non-root paths`() {
            val result = service.normalize("https://example.com/path/")
            assertEquals("https://example.com/path", result)
        }

        @Test
        fun `should keep root path as slash`() {
            val result = service.normalize("https://example.com/")
            assertEquals("https://example.com/", result)
        }

        @Test
        fun `should resolve dot segments`() {
            val result = service.normalize("https://example.com/a/./b")
            assertEquals("https://example.com/a/b", result)
        }

        @Test
        fun `should resolve double dot segments`() {
            val result = service.normalize("https://example.com/a/b/../c")
            assertEquals("https://example.com/a/c", result)
        }

        @Test
        fun `should handle multiple double dots`() {
            val result = service.normalize("https://example.com/a/b/c/../../d")
            assertEquals("https://example.com/a/d", result)
        }

        @Test
        fun `should collapse double slashes`() {
            val result = service.normalize("https://example.com/a//b///c")
            assertEquals("https://example.com/a/b/c", result)
        }

        @Test
        fun `should decode percent-encoded characters in path`() {
            val result = service.normalize("https://example.com/path%20with%20spaces")
            assertEquals("https://example.com/path with spaces", result)
        }
    }

    @Nested
    inner class QueryParameterNormalization {

        @Test
        fun `should remove utm tracking parameters`() {
            val result = service.normalize("https://example.com/path?utm_source=google&utm_medium=cpc&param=value")
            assertEquals("https://example.com/path?param=value", result)
        }

        @Test
        fun `should remove fbclid and gclid`() {
            val result = service.normalize("https://example.com/path?fbclid=123&gclid=456&param=value")
            assertEquals("https://example.com/path?param=value", result)
        }

        @Test
        fun `should remove session identifiers`() {
            val result = service.normalize("https://example.com/path?sessionid=abc&param=value")
            assertEquals("https://example.com/path?param=value", result)
        }

        @Test
        fun `should remove language hints from query params`() {
            val result = service.normalize("https://example.com/path?hsLang=en&param=value")
            assertEquals("https://example.com/path?param=value", result)
        }

        @Test
        fun `should remove cache busting parameters`() {
            val result = service.normalize("https://example.com/path?_=123&timestamp=456&param=value")
            assertEquals("https://example.com/path?param=value", result)
        }

        @Test
        fun `should remove referrer tracking`() {
            val result = service.normalize("https://example.com/path?ref=twitter&source=email&param=value")
            assertEquals("https://example.com/path?param=value", result)
        }

        @Test
        fun `should sort query parameters alphabetically`() {
            val result = service.normalize("https://example.com/path?z=1&a=2&m=3")
            assertEquals("https://example.com/path?a=2&m=3&z=1", result)
        }

        @Test
        fun `should decode percent-encoded query parameters`() {
            val result = service.normalize("https://example.com/path?key=value%20with%20spaces")
            assertEquals("https://example.com/path?key=value with spaces", result)
        }

        @Test
        fun `should handle parameters without values`() {
            val result = service.normalize("https://example.com/path?flag&param=value")
            assertEquals("https://example.com/path?flag&param=value", result)
        }

        @Test
        fun `should remove all insignificant params if only insignificant params present`() {
            val result = service.normalize("https://example.com/path?utm_source=google&fbclid=123")
            assertEquals("https://example.com/path", result)
        }

        @Test
        fun `should handle duplicate parameters by keeping first occurrence`() {
            val result = service.normalize("https://example.com/path?param=first&param=second&other=value")
            assertEquals("https://example.com/path?other=value&param=first", result)
        }
    }

    @Nested
    inner class LocaleHandling {

        @Test
        fun `should keep locale in path by default`() {
            val result = service.normalize("https://example.com/en/about")
            assertEquals("https://example.com/en/about", result)
        }

        @Test
        fun `should strip locale when configured`() {
            val config = UrlNormalizationConfig(stripLocaleFromPath = true)
            val result = service.normalize("https://example.com/en/about", config)
            assertEquals("https://example.com/about", result)
        }

        @Test
        fun `should strip regional locale variants`() {
            val config = UrlNormalizationConfig(stripLocaleFromPath = true)
            val result = service.normalize("https://example.com/en-us/about", config)
            assertEquals("https://example.com/about", result)
        }

        @Test
        fun `should handle multiple locales with whitelist`() {
            val config = UrlNormalizationConfig(
                stripLocaleFromPath = true,
                localeWhitelist = setOf("en", "en-us")
            )
            
            val resultEn = service.normalize("https://example.com/en/about", config)
            assertEquals("https://example.com/about", resultEn)
            
            val resultFr = service.normalize("https://example.com/fr/about", config)
            assertEquals("https://example.com/fr/about", resultFr)
        }

        @Test
        fun `should not strip if locale is the only path segment`() {
            val config = UrlNormalizationConfig(stripLocaleFromPath = true)
            val result = service.normalize("https://example.com/en", config)
            assertEquals("https://example.com/en", result)
        }
    }

    @Nested
    inner class WwwNormalization {

        @Test
        fun `should keep www subdomain by default`() {
            val result = service.normalize("https://www.example.com/path")
            assertEquals("https://www.example.com/path", result)
        }

        @Test
        fun `should remove www when configured`() {
            val config = UrlNormalizationConfig(normalizeWwwSubdomain = true)
            val result = service.normalize("https://www.example.com/path", config)
            assertEquals("https://example.com/path", result)
        }

        @Test
        fun `should not remove www if it's the only part of domain`() {
            val config = UrlNormalizationConfig(normalizeWwwSubdomain = true)
            val result = service.normalize("https://www/path", config)
            assertEquals("https://www/path", result)
        }
    }

    @Nested
    inner class IdnAndIpv6 {

        @Test
        fun `should handle IDN domains by converting to punycode`() {
            val result = service.normalize("https://münchen.de/path")
            // Should convert to punycode and be a valid normalized URL
            assertNotNull(result)
            // The result should start with https:// and contain a valid domain
            assertTrue(result!!.startsWith("https://"))
            assertTrue(result.contains(".de/path"))
            // The domain should be ASCII-compatible (punycode starts with xn--)
            // Note: IDN.toASCII converts ü to punycode
            assertTrue(result.contains("xn--") || result.contains("munchen"))
        }

        @Test
        fun `should handle IPv6 addresses in brackets`() {
            val result = service.normalize("https://[2001:db8::1]/path")
            assertNotNull(result)
            assertTrue(result!!.contains("[2001:db8::1]"))
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `should return null for malformed URLs`() {
            val result = service.normalize("not a url at all")
            assertNull(result)
        }

        @Test
        fun `should return null for empty string`() {
            val result = service.normalize("")
            assertNull(result)
        }

        @Test
        fun `should return null for blank string`() {
            val result = service.normalize("   ")
            assertNull(result)
        }

        @Test
        fun `should return null for non-http schemes`() {
            assertNull(service.normalize("ftp://example.com/path"))
            assertNull(service.normalize("file:///path/to/file"))
            assertNull(service.normalize("data:text/plain,hello"))
        }

        @Test
        fun `should return null for URLs without host`() {
            val result = service.normalize("https:///path")
            assertNull(result)
        }

        @Test
        fun `should handle URLs with only query parameters`() {
            val result = service.normalize("https://example.com?a=1&b=2")
            assertEquals("https://example.com/?a=1&b=2", result)
        }

        @Test
        fun `should handle empty query string`() {
            val result = service.normalize("https://example.com/path?")
            assertEquals("https://example.com/path", result)
        }
    }

    @Nested
    inner class RealWorldExamples {

        @Test
        fun `should normalize OT and P URLs with hsLang parameter`() {
            val url1 = "https://www.otandp.com/body-check/standard?hsLang=en"
            val url2 = "https://www.otandp.com/body-check/standard"
            
            val normalized1 = service.normalize(url1)
            val normalized2 = service.normalize(url2)
            
            assertEquals(normalized1, normalized2)
            assertEquals("https://www.otandp.com/body-check/standard", normalized1)
        }

        @Test
        fun `should normalize URLs with different UTM parameters`() {
            val url1 = "https://example.com/page?utm_source=facebook&utm_campaign=spring"
            val url2 = "https://example.com/page?utm_source=google&utm_campaign=winter"
            val url3 = "https://example.com/page"
            
            val normalized1 = service.normalize(url1)
            val normalized2 = service.normalize(url2)
            val normalized3 = service.normalize(url3)
            
            assertEquals(normalized1, normalized2)
            assertEquals(normalized1, normalized3)
            assertEquals("https://example.com/page", normalized1)
        }

        @Test
        fun `should normalize URLs with mixed insignificant and significant params`() {
            val url = "https://example.com/search?q=kotlin&utm_source=google&sort=date&fbclid=123"
            val result = service.normalize(url)
            assertEquals("https://example.com/search?q=kotlin&sort=date", result)
        }

        @Test
        fun `should normalize URLs with trailing slashes and fragments`() {
            val url1 = "https://example.com/page/#section"
            val url2 = "https://example.com/page#section"
            val url3 = "https://example.com/page/"
            val url4 = "https://example.com/page"
            
            val results = listOf(url1, url2, url3, url4).map { service.normalize(it) }
            
            // All should normalize to the same URL
            assertTrue(results.all { it == results.first() })
            assertEquals("https://example.com/page", results.first())
        }

        @Test
        fun `should normalize complex URLs with all features`() {
            val url = "HTTPS://WWW.Example.Com:443/en/path/./to/../page/?z=3&utm_source=test&a=1&hsLang=en#section"
            val result = service.normalize(url)
            assertEquals("https://www.example.com/en/path/page?a=1&z=3", result)
        }
    }
}

