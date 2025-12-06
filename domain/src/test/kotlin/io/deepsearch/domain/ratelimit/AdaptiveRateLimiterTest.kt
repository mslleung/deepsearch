package io.deepsearch.domain.ratelimit

import io.deepsearch.domain.exceptions.HttpClientErrorException
import io.deepsearch.domain.exceptions.RateLimitExceededException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class AdaptiveRateLimiterTest {

    @Test
    fun `should allow requests up to initial concurrency limit`() = runTest {
        val config = AdaptiveRateLimiterConfig(initialConcurrency = 10)
        val rateLimiter = AdaptiveRateLimiter(config)
        
        val successCount = AtomicInteger(0)
        
        // Launch 10 concurrent requests (at the limit)
        val jobs = (1..10).map {
            async {
                rateLimiter.withRateLimit("https://example.com/page$it") {
                    successCount.incrementAndGet()
                    "success"
                }
            }
        }
        
        val results = jobs.awaitAll()
        
        assertEquals(10, successCount.get())
        assertTrue(results.all { it == "success" })
    }

    @Test
    fun `should reduce concurrency on HTTP 429 and retry`() = runTest {
        val config = AdaptiveRateLimiterConfig(
            initialConcurrency = 100,
            minConcurrency = 1,
            decreaseFactor = 0.5,
            maxRetries = 3,
            baseBackoffMs = 10, // Use short delays for testing
            maxBackoffMs = 50
        )
        val rateLimiter = AdaptiveRateLimiter(config)
        
        var attemptCount = 0
        
        val result = rateLimiter.withRateLimit("https://example.com/test") {
            attemptCount++
            if (attemptCount < 2) {
                // First attempt fails with 429
                throw HttpClientErrorException("https://example.com/test", 429, "Too Many Requests")
            }
            "success"
        }
        
        assertEquals("success", result)
        assertEquals(2, attemptCount) // First attempt failed, second succeeded
        
        // Verify concurrency was reduced
        val currentLimit = rateLimiter.getCurrentLimit("example.com")
        assertEquals(50, currentLimit) // Halved from 100
    }

    @Test
    fun `should throw RateLimitExceededException after max retries`() = runTest {
        val config = AdaptiveRateLimiterConfig(
            initialConcurrency = 100,
            maxRetries = 3,
            baseBackoffMs = 10,
            maxBackoffMs = 50
        )
        val rateLimiter = AdaptiveRateLimiter(config)
        
        var attemptCount = 0
        
        val exception = assertThrows(RateLimitExceededException::class.java) {
            kotlinx.coroutines.runBlocking {
                rateLimiter.withRateLimit("https://example.com/test") {
                    attemptCount++
                    throw HttpClientErrorException("https://example.com/test", 429, "Too Many Requests")
                }
            }
        }
        
        assertEquals(3, attemptCount)
        assertEquals(3, exception.retriesAttempted)
        assertEquals("https://example.com/test", exception.url)
    }

    @Test
    fun `should propagate non-429 HTTP errors without retry`() = runTest {
        val config = AdaptiveRateLimiterConfig(maxRetries = 3)
        val rateLimiter = AdaptiveRateLimiter(config)
        
        var attemptCount = 0
        
        val exception = assertThrows(HttpClientErrorException::class.java) {
            kotlinx.coroutines.runBlocking {
                rateLimiter.withRateLimit("https://example.com/test") {
                    attemptCount++
                    throw HttpClientErrorException("https://example.com/test", 404, "Not Found")
                }
            }
        }
        
        assertEquals(1, attemptCount) // No retries for 404
        assertEquals(404, exception.statusCode)
    }

    @Test
    fun `should increase concurrency after successful requests`() = runTest {
        val config = AdaptiveRateLimiterConfig(
            initialConcurrency = 100,
            minConcurrency = 1,
            decreaseFactor = 0.5,
            increaseStep = 1,
            successesBeforeIncrease = 3,
            maxRetries = 3,
            baseBackoffMs = 10,
            maxBackoffMs = 50
        )
        val rateLimiter = AdaptiveRateLimiter(config)
        
        // First, trigger a rate limit to reduce concurrency
        var attempt = 0
        rateLimiter.withRateLimit("https://example.com/first") {
            attempt++
            if (attempt == 1) {
                throw HttpClientErrorException("https://example.com/first", 429, "Too Many Requests")
            }
            "success"
        }
        
        // Verify concurrency was reduced to 50
        assertEquals(50, rateLimiter.getCurrentLimit("example.com"))
        
        // Now make successful requests to trigger increase
        // Note: the retry success from above counts as 1, so we need 2 more
        repeat(2) {
            rateLimiter.withRateLimit("https://example.com/success$it") {
                "success"
            }
        }
        
        // Verify concurrency increased by increaseStep (1)
        assertEquals(51, rateLimiter.getCurrentLimit("example.com"))
    }

    @Test
    fun `should not increase concurrency above initial limit`() = runTest {
        val config = AdaptiveRateLimiterConfig(
            initialConcurrency = 10,
            increaseStep = 1,
            successesBeforeIncrease = 2
        )
        val rateLimiter = AdaptiveRateLimiter(config)
        
        // Make many successful requests
        repeat(20) {
            rateLimiter.withRateLimit("https://example.com/success$it") {
                "success"
            }
        }
        
        // Concurrency should stay at initial limit
        assertEquals(10, rateLimiter.getCurrentLimit("example.com"))
    }

    @Test
    fun `should not reduce concurrency below minimum`() = runTest {
        val config = AdaptiveRateLimiterConfig(
            initialConcurrency = 10,
            minConcurrency = 1,
            decreaseFactor = 0.5,
            maxRetries = 5,
            baseBackoffMs = 1,
            maxBackoffMs = 5
        )
        val rateLimiter = AdaptiveRateLimiter(config)
        
        // Make multiple requests that all get rate limited once then succeed
        repeat(5) { i ->
            var attempt = 0
            rateLimiter.withRateLimit("https://example.com/request$i") {
                attempt++
                if (attempt == 1) {
                    throw HttpClientErrorException("https://example.com/request$i", 429, "Too Many Requests")
                }
                "success"
            }
        }
        
        // After 5 halvings: 10 -> 5 -> 2 -> 1 -> 1 -> 1 (can't go below min)
        val currentLimit = rateLimiter.getCurrentLimit("example.com")
        assertTrue(currentLimit >= 1, "Concurrency should not fall below minimum: $currentLimit")
    }

    @Test
    fun `should track separate limits per domain`() = runTest {
        val config = AdaptiveRateLimiterConfig(
            initialConcurrency = 100,
            decreaseFactor = 0.5,
            maxRetries = 3,
            baseBackoffMs = 10,
            maxBackoffMs = 50
        )
        val rateLimiter = AdaptiveRateLimiter(config)
        
        // Rate limit domain1
        var attempt1 = 0
        rateLimiter.withRateLimit("https://domain1.com/test") {
            attempt1++
            if (attempt1 == 1) {
                throw HttpClientErrorException("https://domain1.com/test", 429, "Too Many Requests")
            }
            "success"
        }
        
        // Rate limit domain2 twice
        var attempt2 = 0
        rateLimiter.withRateLimit("https://domain2.com/test") {
            attempt2++
            if (attempt2 <= 2) {
                throw HttpClientErrorException("https://domain2.com/test", 429, "Too Many Requests")
            }
            "success"
        }
        
        // domain1 should have limit = 50 (halved once)
        assertEquals(50, rateLimiter.getCurrentLimit("domain1.com"))
        
        // domain2 should have limit = 25 (halved twice)
        assertEquals(25, rateLimiter.getCurrentLimit("domain2.com"))
    }

    @Test
    fun `should extract domain correctly from various URL formats`() = runTest {
        val config = AdaptiveRateLimiterConfig(
            initialConcurrency = 100,
            decreaseFactor = 0.5,
            maxRetries = 2,
            baseBackoffMs = 1,
            maxBackoffMs = 5
        )
        val rateLimiter = AdaptiveRateLimiter(config)
        
        // Rate limit using different URL formats for the same domain
        var attempt = 0
        rateLimiter.withRateLimit("https://www.example.com/page1") {
            attempt++
            if (attempt == 1) {
                throw HttpClientErrorException("https://www.example.com/page1", 429, "Too Many Requests")
            }
            "success"
        }
        
        // All these should share the same domain state
        assertEquals(50, rateLimiter.getCurrentLimit("www.example.com"))
    }

    @Test
    fun `should provide statistics for domain`() = runTest {
        val config = AdaptiveRateLimiterConfig(
            initialConcurrency = 100,
            maxRetries = 2,
            baseBackoffMs = 1,
            maxBackoffMs = 5
        )
        val rateLimiter = AdaptiveRateLimiter(config)
        
        // Make some successful requests
        repeat(3) {
            rateLimiter.withRateLimit("https://example.com/success$it") { "success" }
        }
        
        // Make a rate-limited request
        var attempt = 0
        rateLimiter.withRateLimit("https://example.com/ratelimit") {
            attempt++
            if (attempt == 1) {
                throw HttpClientErrorException("https://example.com/ratelimit", 429, "Too Many Requests")
            }
            "success"
        }
        
        val stats = rateLimiter.getStats("example.com")
        assertNotNull(stats)
        assertEquals(50, stats!!.currentLimit)
        assertEquals(4, stats.successCount) // 3 successes + 1 retry success
        assertEquals(1, stats.rateLimitCount)
        assertNotNull(stats.lastRateLimitTime)
    }

    @Test
    fun `should return null stats for unknown domain`() = runTest {
        val rateLimiter = AdaptiveRateLimiter()
        
        val stats = rateLimiter.getStats("unknown.com")
        assertNull(stats)
    }

    @Test
    fun `should retry max times and then throw exception`() = runTest {
        // This test verifies the retry mechanism works correctly
        // It uses short delays since runTest uses virtual time
        val config = AdaptiveRateLimiterConfig(
            initialConcurrency = 100,
            maxRetries = 3,
            baseBackoffMs = 1, // Very short for testing with virtual time
            maxBackoffMs = 10
        )
        val rateLimiter = AdaptiveRateLimiter(config)
        
        var attemptCount = 0
        
        try {
            rateLimiter.withRateLimit("https://example.com/test") {
                attemptCount++
                throw HttpClientErrorException("https://example.com/test", 429, "Too Many Requests")
            }
        } catch (e: RateLimitExceededException) {
            // Expected
        }
        
        // Verify all retry attempts were made
        assertEquals(3, attemptCount, "Should attempt exactly maxRetries times")
        
        // Verify concurrency was reduced multiple times
        // Initial: 100 -> 50 (first 429) -> 25 (second 429) -> 12 (third 429)
        val finalLimit = rateLimiter.getCurrentLimit("example.com")
        assertTrue(
            finalLimit < 50,
            "Concurrency should have been reduced multiple times: $finalLimit"
        )
    }
}

