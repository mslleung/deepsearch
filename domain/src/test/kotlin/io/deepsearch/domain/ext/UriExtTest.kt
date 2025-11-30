package io.deepsearch.domain.ext

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class UriExtTest {

    @Test
    fun `toSafeUri parses valid URL`() {
        // Given
        val url = "https://example.com/path?query=value"
        
        // When
        val uri = url.toSafeUri()
        
        // Then
        assertEquals("https", uri.scheme)
        assertEquals("example.com", uri.host)
        assertEquals("/path", uri.path)
        assertEquals("query=value", uri.query)
    }

    @Test
    fun `toSafeUri encodes spaces in query parameter`() {
        // Given
        val url = "https://example.com/path?text=hello world"
        
        // When
        val uri = url.toSafeUri()
        
        // Then
        assertEquals("https", uri.scheme)
        assertEquals("example.com", uri.host)
        assertEquals("/path", uri.path)
        assertEquals("text=hello%20world", uri.rawQuery)
    }

    @Test
    fun `toSafeUri encodes newlines in query parameter`() {
        // Given - WhatsApp-style URL with newlines in the text parameter
        val url = "https://wa.me/85264522442?text=\n\nHi AgentFlow, boleh jelaskan lebih detail lagi mengenai SleekFlow?"
        
        // When
        val uri = url.toSafeUri()
        
        // Then
        assertEquals("https", uri.scheme)
        assertEquals("wa.me", uri.host)
        assertEquals("/85264522442", uri.path)
        // Verify the URL can be parsed and contains the encoded newlines
        assertEquals("text=%0A%0AHi%20AgentFlow,%20boleh%20jelaskan%20lebih%20detail%20lagi%20mengenai%20SleekFlow?", uri.rawQuery)
    }

    @Test
    fun `toSafeUri encodes carriage returns in query parameter`() {
        // Given
        val url = "https://example.com/path?text=line1\r\nline2"
        
        // When
        val uri = url.toSafeUri()
        
        // Then
        assertEquals("text=line1%0D%0Aline2", uri.rawQuery)
    }

    @Test
    fun `toSafeUri encodes spaces in path`() {
        // Given
        val url = "https://example.com/path with spaces/file.html"
        
        // When
        val uri = url.toSafeUri()
        
        // Then
        assertEquals("/path%20with%20spaces/file.html", uri.rawPath)
    }

    @Test
    fun `toSafeUri throws for blank URL`() {
        // Given
        val url = "   "
        
        // When/Then
        assertThrows<IllegalArgumentException> {
            url.toSafeUri()
        }
    }

    @Test
    fun `toSafeUri handles URL with fragment`() {
        // Given
        val url = "https://example.com/path?query=value#section with space"
        
        // When
        val uri = url.toSafeUri()
        
        // Then
        assertEquals("section%20with%20space", uri.rawFragment)
    }

    @Test
    fun `toSafeUri handles special characters in query`() {
        // Given
        val url = "https://example.com/path?text=<hello>"
        
        // When
        val uri = url.toSafeUri()
        
        // Then
        assertEquals("text=%3Chello%3E", uri.rawQuery)
    }
}

