package io.deepsearch.application.services

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import io.deepsearch.application.dto.WebScrapeRequest
import io.deepsearch.application.dto.WebScrapeResponse
import io.deepsearch.application.dto.toDomain
import io.deepsearch.application.dto.toResponse
import io.deepsearch.domain.entities.WebScrapeQuery
import io.deepsearch.domain.entities.WebScrapeResult
import io.deepsearch.domain.exceptions.WebScrapeException
import io.deepsearch.domain.exceptions.WebScrapeTimeoutException
import io.deepsearch.domain.exceptions.AiInterpretationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory

class WebScrapeService(
    private val openAI: OpenAI
) {
    private val logger = LoggerFactory.getLogger(WebScrapeService::class.java)
    
    suspend fun scrapeAndInterpret(request: WebScrapeRequest): WebScrapeResponse {
        val startTime = System.currentTimeMillis()
        
        try {
            logger.info("Starting web scraping for URL: ${request.url}")
            
            val domainQuery = request.toDomain()
            val scrapedContent = scrapeWebsite(domainQuery)
            val interpretedResponse = interpretContent(scrapedContent, domainQuery)
            
            val result = WebScrapeResult(
                url = domainQuery.url,
                query = domainQuery.query,
                response = interpretedResponse,
                timestamp = System.currentTimeMillis()
            )
            
            logger.info("Web scraping completed successfully for URL: ${request.url}")
            return result.toResponse()
            
        } catch (e: Exception) {
            logger.error("Web scraping failed for URL: ${request.url}", e)
            throw when (e) {
                is TimeoutCancellationException -> WebScrapeTimeoutException(request.url)
                is WebScrapeException, is AiInterpretationException -> e
                else -> WebScrapeException("Unexpected error: ${e.message}")
            }
        }
    }
    
    private suspend fun scrapeWebsite(query: WebScrapeQuery): String {
        return withTimeout(30000) { // 30 second timeout
            try {
                Playwright.create().use { playwright ->
                    val browser = playwright.chromium().launch(
                        BrowserType.LaunchOptions()
                            .setHeadless(true)
                            .setSlowMo(100.0)
                    )
                    
                    val page = browser.newPage()
                    
                    // Configure page settings
                    page.setDefaultTimeout(10000.0) // 10 second timeout for page operations
                    
                    // Set user agent to appear more like a real browser
                    page.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                    
                    // Navigate to the page
                    page.navigate(query.url.value)
                    
                    // Wait for the page to load
                    page.waitForLoadState()
                    
                    // Extract text content from the page
                    val textContent = page.evaluate("document.body.innerText").toString()
                    
                    browser.close()
                    
                    if (textContent.isBlank()) {
                        throw WebScrapeException("No content found on the page")
                    }
                    
                    textContent
                }
            } catch (e: Exception) {
                logger.error("Error during web scraping", e)
                throw WebScrapeException("Failed to scrape content: ${e.message}")
            }
        }
    }
    
    private suspend fun interpretContent(content: String, query: WebScrapeQuery): String {
        try {
            val prompt = buildPrompt(content, query.query.value)
            
            val chatRequest = ChatCompletionRequest(
                model = ModelId("gpt-3.5-turbo"),
                messages = listOf(
                    ChatMessage(
                        role = ChatRole.System,
                        content = "You are a helpful assistant that analyzes web content and answers questions based on the provided content. " +
                                "Always base your answers on the content provided. If the content doesn't contain information to answer the question, " +
                                "say so explicitly. Be accurate and concise in your responses."
                    ),
                    ChatMessage(
                        role = ChatRole.User,
                        content = prompt
                    )
                ),
                maxTokens = 1000
            )
            
            val response = openAI.chatCompletion(chatRequest)
            val answer = response.choices.firstOrNull()?.message?.content
            
            if (answer.isNullOrBlank()) {
                throw AiInterpretationException("No response from AI model")
            }
            
            return answer
            
        } catch (e: Exception) {
            logger.error("Error during AI interpretation", e)
            throw AiInterpretationException("Failed to interpret content: ${e.message}")
        }
    }
    
    private fun buildPrompt(content: String, query: String): String {
        return """
            Based on the following website content, please answer this question: "$query"
            
            Website content:
            ${content.take(8000)} ${if (content.length > 8000) "..." else ""}
            
            Please provide a clear and accurate answer based on the content above.
        """.trimIndent()
    }
} 