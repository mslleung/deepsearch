package io.deepsearch.domain.browser.playwright

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import io.deepsearch.domain.browser.IBrowser
import io.deepsearch.domain.browser.IBrowserContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Playwright-backed browser instance created from a runtime.
 *
 * Each browser has its own mutex to ensure thread-safe access to the Playwright API,
 * allowing multiple browsers from the same runtime to operate concurrently.
 * Browsers are typically created per-link during web scraping.
 */
class PlaywrightBrowser(
    private val playwright: Playwright
) : IBrowser {
    private val playwrightBrowser = playwright.chromium().launch(
        BrowserType.LaunchOptions()
            .setHeadless(true)
            .setArgs(
                listOf(
                    "--disable-blink-features=AutomationControlled",
                    "--disable-dev-shm-usage",
                    "--no-sandbox",
                    "--disable-setuid-sandbox",
                    "--disable-web-security",
                    "--disable-features=IsolateOrigins,site-per-process",
                    "--window-size=1920,1080",
                    "--disable-infobars",
                    "--disable-extensions",
                    "--disable-background-networking",
                    "--disable-background-timer-throttling",
                    "--disable-backgrounding-occluded-windows",
                    "--disable-renderer-backgrounding",
                    "--disable-ipc-flooding-protection"
                )
            )
    )
    private val apiMutex: Mutex = Mutex()

    override suspend fun createContext(): IBrowserContext {
        val context = apiMutex.withLock {
            playwrightBrowser.newContext(
                Browser.NewContextOptions()
                    .setUserAgent(
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                    )
                    .setViewportSize(1920, 1080)
                    .setLocale("en-US")
                    .setTimezoneId("America/New_York")
                    .setPermissions(listOf("notifications", "geolocation"))
                    .setExtraHTTPHeaders(
                        mapOf(
                            "Accept-Language" to "en-US,en;q=0.9",
                            "Accept-Encoding" to "gzip, deflate, br",
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                            "Sec-Fetch-Site" to "none",
                            "Sec-Fetch-Mode" to "navigate",
                            "Sec-Fetch-User" to "?1",
                            "Sec-Fetch-Dest" to "document",
                            "Sec-Ch-Ua" to "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
                            "Sec-Ch-Ua-Mobile" to "?0",
                            "Sec-Ch-Ua-Platform" to "\"Windows\""
                        )
                    )
                    .setJavaScriptEnabled(true)
                    .setBypassCSP(true)
            )
        }

        // Set up route blocking for expensive resource types
        context.route("**/*") { route ->
            val resourceType = route.request().resourceType()
            when (resourceType) {
                "media",      // video/audio
                "manifest",   // app manifests
                "eventsource",
                "websocket",  // real-time connections
                "texttrack"   // video subtitles
                -> route.abort()
                else -> route.resume()
            }
        }

        return PlaywrightBrowserContext(context, apiMutex)
    }

    override suspend fun close() {
        apiMutex.withLock { 
            playwrightBrowser.close() 
        }
    }
}