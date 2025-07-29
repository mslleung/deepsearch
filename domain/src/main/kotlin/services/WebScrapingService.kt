package io.deepsearch.domain.services

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import io.deepsearch.domain.exceptions.WebScrapingException

class WebScrapingService {
    
    suspend fun scrapeWebsite(url: String): String {
        return try {
            val playwright = Playwright.create()
            val browser = playwright.chromium().launch(
                BrowserType.LaunchOptions().setHeadless(true)
            )
            
            val page = browser.newPage()
            
            // Configure page settings for better scraping
            page.setDefaultTimeout(30000.0) // 30 seconds timeout
            
            // Navigate to the URL
            page.navigate(url)
            
            // Wait for the page to load completely
            page.waitForLoadState()
            
            // Extract the HTML content
            val htmlContent = page.content()
            
            // Clean up resources
            page.close()
            browser.close()
            playwright.close()
            
            htmlContent
            
        } catch (exception: Exception) {
            throw WebScrapingException("Failed to scrape website: ${url}", exception)
        }
    }
} 