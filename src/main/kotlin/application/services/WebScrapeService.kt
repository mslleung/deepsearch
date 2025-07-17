package io.deepsearch.application.services

import com.google.adk.agents.LlmAgent
import com.google.adk.events.Event
import com.google.adk.runner.InMemoryRunner
import com.google.adk.sessions.Session
import com.google.adk.tools.FunctionTool
import com.google.adk.tools.Annotations.Schema
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
import org.slf4j.Logger
import io.reactivex.rxjava3.core.Flowable
import java.util.concurrent.CompletableFuture

/**
 * AI Agent-based web scraping service using Google ADK
 * Creates intelligent agents that can plan, scrape, and interpret web content autonomously
 */
class WebScrapeService(
    private val logger: Logger
) {
    
    companion object {
        private const val USER_ID = "webscrape_user"
        private const val AGENT_NAME = "web_scraping_agent"
    }
    
    // ADK Agent with web scraping capabilities
    private val webScrapingAgent = createWebScrapingAgent()
    private val runner = InMemoryRunner(webScrapingAgent)
    
    suspend fun scrapeAndInterpret(request: WebScrapeRequest): WebScrapeResponse {
        val startTime = System.currentTimeMillis()
        
        try {
            logger.info("ADK Agent starting autonomous web scraping task for URL: ${request.url}")
            
            val domainQuery = request.toDomain()
            
            // Create ADK session for this scraping task
            val session = createSession()
            
            // Build agent prompt with the scraping request
            val agentPrompt = buildAgentPrompt(domainQuery)
            
            // Execute the agent
            val agentResponse = executeAgent(session, agentPrompt)
            
            val result = WebScrapeResult(
                url = domainQuery.url,
                query = domainQuery.query,
                response = agentResponse,
                timestamp = System.currentTimeMillis()
            )
            
            logger.info("ADK Agent completed autonomous task successfully for URL: ${request.url}")
            return result.toResponse()
            
        } catch (e: Exception) {
            logger.error("ADK Agent failed to complete task for URL: ${request.url}", e)
            throw when (e) {
                is TimeoutCancellationException -> WebScrapeTimeoutException(request.url)
                is WebScrapeException, is AiInterpretationException -> e
                else -> WebScrapeException("Agent execution error: ${e.message}")
            }
        }
    }
    
    private fun createWebScrapingAgent() = LlmAgent.builder()
        .name(AGENT_NAME)
        .model("gemini-2.0-flash")
        .description("An intelligent web scraping agent that can fetch and analyze web content")
        .instruction("""
            You are an intelligent web scraping and analysis agent. You have access to web scraping tools.
            
            When given a URL and a query:
            1. Use the scrape_website tool to fetch content from the URL
            2. Analyze the scraped content in relation to the user's query
            3. Provide a comprehensive and accurate answer based on the content
            
            Always:
            - Base your answers strictly on the scraped content
            - Be thorough and accurate in your analysis
            - If the content doesn't contain relevant information, say so explicitly
            - Highlight key findings that directly address the user's query
        """.trimIndent())
        .tools(
            FunctionTool.create(WebScrapeService::class.java, "scrapeWebsite")
        )
        .build()
    
    private suspend fun createSession(): Session {
        return CompletableFuture.supplyAsync {
            runner.sessionService()
                .createSession(AGENT_NAME, USER_ID)
                .blockingGet()
        }.get()
    }
    
    private fun buildAgentPrompt(query: WebScrapeQuery): String {
        return """
            Please scrape and analyze the website: ${query.url.value}
            
            Query: ${query.query.value}
            
            Use the scrape_website tool to fetch the content, then provide a detailed analysis 
            that answers the query based on what you find on the page.
        """.trimIndent()
    }
    
    private suspend fun executeAgent(session: Session, prompt: String): String {
        return CompletableFuture.supplyAsync {
            // Use ADK's message passing system
            val events: Flowable<Event> = runner.runAsync(USER_ID, session.id(), prompt)
            
            val responses = mutableListOf<String>()
            events.blockingForEach { event ->
                val content = event.stringifyContent()
                if (!content.isNullOrBlank()) {
                    responses.add(content)
                }
            }
            
            responses.joinToString("\n").ifBlank { 
                throw AiInterpretationException("No response from agent") 
            }
        }.get()
    }
    
    companion object {
        /**
         * ADK Tool Function: Scrapes website content
         * This function is called by the ADK agent when it needs to fetch web content
         */
        @JvmStatic
        fun scrapeWebsite(
            @Schema(
                name = "url",
                description = "The URL of the website to scrape"
            ) url: String
        ): Map<String, Any> {
            return try {
                val content = performWebScraping(url)
                mapOf(
                    "status" to "success",
                    "url" to url,
                    "content" to content,
                    "content_length" to content.length,
                    "message" to "Successfully scraped website content"
                )
            } catch (e: Exception) {
                mapOf(
                    "status" to "error",
                    "url" to url,
                    "error" to e.message,
                    "message" to "Failed to scrape website: ${e.message}"
                )
            }
        }
        
        private fun performWebScraping(url: String): String {
            return Playwright.create().use { playwright ->
                val browser = playwright.chromium()
                    .launch(
                        BrowserType.LaunchOptions()
                            .setHeadless(true)
                            .setSlowMo(100.0)
                    )
                
                val page = browser.newPage()
                
                try {
                    // Configure page settings
                    page.setDefaultTimeout(10000.0) // 10 second timeout
                    
                    // Navigate to the page
                    page.navigate(url)
                    
                    // Wait for the page to load
                    page.waitForLoadState()
                    
                    // Extract text content from the page
                    val textContent = page.evaluate("document.body.innerText").toString()
                    
                    if (textContent.isBlank()) {
                        throw WebScrapeException("No content found on the page")
                    }
                    
                    // Limit content size for processing
                    textContent.take(10000)
                    
                } finally {
                    browser.close()
                }
            }
        }
    }
} 