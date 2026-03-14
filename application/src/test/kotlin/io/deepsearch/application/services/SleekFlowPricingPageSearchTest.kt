package io.deepsearch.application.services

import io.deepsearch.application.config.applicationBenchmarkTestModule
import io.deepsearch.application.services.benchmark.ActionEfficiencyAnalyzer
import io.deepsearch.domain.agents.NavigationAction
import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import io.deepsearch.domain.testing.IsolatedKoinTest
import org.koin.java.KoinJavaComponent.inject

/**
 * Focused integration tests against https://sleekflow.io/pricing to validate the VLM
 * agent's ability to parse div-soup comparison tables -- feature grids rendered as
 * nested divs rather than semantic <table> elements.
 *
 * Tests stress:
 * - Mapping feature rows to plan columns (Free / Pro / Premium / Enterprise)
 * - Understanding visual symbols (checkmark, X, "Add-on") in a grid context
 * - Scrolling through a very long comparison page
 * - Clicking FAQ accordions to reveal hidden content
 * - Cross-column comparisons (e.g., "which plans include X?")
 *
 * Requires deepsearch-browser running at localhost:8090 and GOOGLE_API_KEY env var.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.CONCURRENT)
class SleekFlowPricingPageSearchTest : IsolatedKoinTest() {

    private lateinit var koinApp: KoinApplication
    private val agenticSearchService by inject<IAgenticWebpageSearchService>()
    private val applicationScope by inject<IApplicationCoroutineScope>()

    companion object {
        private const val PRICING_URL = "https://sleekflow.io/pricing"
    }

    @BeforeAll
    fun setup() {
        koinApp = koinApplication {
            modules(applicationBenchmarkTestModule)
        }
        koinApp.createEagerInstances()
        testKoin = koinApp.koin
    }

    @AfterAll
    fun teardown() {
        applicationScope.close()
        koinApp.close()
    }

    // ==================== Group 1: Plan Pricing (top of page) ====================

    @Test
    fun `plan price - Pro AI`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = PRICING_URL,
            query = "What is the price of the SleekFlow Pro AI plan?",
            sessionId = QuerySessionId("sf-pro-price")
        )

        println("=== PRO AI PRICE ===")
        printResult(result)

        assertTrue(result.success, "Should find Pro plan price")
        assertNotNull(result.answer)
        assertTrue(
            result.answer!!.contains("99") || result.answer!!.contains("149"),
            "Pro AI plan should show a price: ${result.answer}"
        )
    }

    @Test
    fun `plan price - Premium AI`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = PRICING_URL,
            query = "What is the price of the SleekFlow Premium AI plan?",
            sessionId = QuerySessionId("sf-premium-price")
        )

        println("=== PREMIUM AI PRICE ===")
        printResult(result)

        assertTrue(result.success, "Should find Premium plan price")
        assertNotNull(result.answer)
        assertTrue(
            result.answer!!.contains("299") || result.answer!!.contains("349"),
            "Premium AI plan should show a price: ${result.answer}"
        )
    }

    // ==================== Group 2: Omnichannel Table (div-soup grid) ====================

    @Test
    fun `omnichannel table - monthly active contacts across plans`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = PRICING_URL,
            query = "How many monthly active contacts (MACs) are included in each SleekFlow plan? List the MAC count for Free, Pro, and Premium.",
            sessionId = QuerySessionId("sf-macs")
        )

        println("=== MACs PER PLAN ===")
        printResult(result)

        assertTrue(result.success, "Should find MAC info")
        assertNotNull(result.answer)
        val answer = result.answer!!
        assertTrue(answer.contains("50"), "Free plan should have 50 MACs: $answer")
        assertTrue(answer.contains("500"), "Pro plan should have 500 MACs: $answer")
        assertTrue(
            answer.contains("1000") || answer.contains("1,000"),
            "Premium plan should have 1000 MACs: $answer"
        )
    }

    @Test
    fun `omnichannel table - user accounts for Premium plan`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = PRICING_URL,
            query = "How many user accounts are included in the SleekFlow Premium AI plan?",
            sessionId = QuerySessionId("sf-premium-users")
        )

        println("=== PREMIUM USER ACCOUNTS ===")
        printResult(result)

        assertTrue(result.success, "Should find Premium user account info")
        assertNotNull(result.answer)
        assertTrue(
            result.answer!!.contains("5") || result.answer!!.contains("10"),
            "Premium plan should include 5 or 10 user accounts: ${result.answer}"
        )
    }

    @Test
    fun `omnichannel table - number of channels across plans`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = PRICING_URL,
            query = "How many channels does each SleekFlow plan support? List the number for Free, Pro, Premium, and Enterprise.",
            sessionId = QuerySessionId("sf-channels")
        )

        println("=== CHANNELS PER PLAN ===")
        printResult(result)

        assertTrue(result.success, "Should find channel info across plans")
        assertNotNull(result.answer)
        val answer = result.answer!!
        assertTrue(answer.contains("3"), "Free and Pro should support 3 channels: $answer")
        assertTrue(answer.contains("10"), "Premium should support 10 channels: $answer")
        assertTrue(
            answer.lowercase().contains("unlimited"),
            "Enterprise should have unlimited channels: $answer"
        )
    }

    @Test
    fun `omnichannel table - Flow Builder active flow limits`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = PRICING_URL,
            query = "How many active flows are allowed in the Flow Builder for each SleekFlow plan? List the limits for Free, Pro, Premium, and Enterprise.",
            sessionId = QuerySessionId("sf-flow-limits")
        )

        println("=== FLOW BUILDER LIMITS ===")
        printResult(result)

        assertTrue(result.success, "Should find Flow Builder limits")
        assertNotNull(result.answer)
        val answer = result.answer!!
        assertTrue(answer.contains("50"), "Premium should allow 50 active flows: $answer")
        assertTrue(answer.contains("200"), "Enterprise should allow 200 active flows: $answer")
    }

    @Test
    fun `omnichannel table - analytics dashboard availability`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = PRICING_URL,
            query = "Which SleekFlow plans include the analytics dashboard feature?",
            sessionId = QuerySessionId("sf-analytics")
        )

        println("=== ANALYTICS DASHBOARD ===")
        printResult(result)

        assertTrue(result.success, "Should find analytics dashboard info")
        assertNotNull(result.answer)
        val answer = result.answer!!.lowercase()
        assertTrue(
            answer.contains("premium") || answer.contains("enterprise"),
            "Analytics dashboard should be on Premium and/or Enterprise: ${result.answer}"
        )
    }

    // ==================== Group 3: Security Section (div-soup grid) ====================

    @Test
    fun `security table - role-based access control`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = PRICING_URL,
            query = "Which SleekFlow plans include role-based access control? Is it available on the Free or Pro plan?",
            sessionId = QuerySessionId("sf-rbac")
        )

        println("=== ROLE-BASED ACCESS CONTROL ===")
        printResult(result)

        assertTrue(result.success, "Should find RBAC info")
        assertNotNull(result.answer)
        val answer = result.answer!!.lowercase()
        assertTrue(
            answer.contains("premium") || answer.contains("enterprise"),
            "RBAC should be on Premium and/or Enterprise: ${result.answer}"
        )
    }

    @Test
    fun `security table - PII masking`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = PRICING_URL,
            query = "Which SleekFlow plan includes PII (personally identifiable information) masking?",
            sessionId = QuerySessionId("sf-pii")
        )

        println("=== PII MASKING ===")
        printResult(result)

        assertTrue(result.success, "Should find PII masking info")
        assertNotNull(result.answer)
        val answer = result.answer!!.lowercase()
        assertTrue(
            answer.contains("enterprise"),
            "PII masking should be Enterprise only: ${result.answer}"
        )
    }

    @Test
    fun `security table - IP allowlisting`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = PRICING_URL,
            query = "Which SleekFlow plan includes IP allowlisting?",
            sessionId = QuerySessionId("sf-ip-allowlist")
        )

        println("=== IP ALLOWLISTING ===")
        printResult(result)

        assertTrue(result.success, "Should find IP allowlisting info")
        assertNotNull(result.answer)
        val answer = result.answer!!.lowercase()
        assertTrue(
            answer.contains("enterprise"),
            "IP allowlisting should be Enterprise only: ${result.answer}"
        )
    }

    // ==================== Group 4: Support Section (from the screenshot) ====================

    @Test
    fun `support table - SLA availability`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = PRICING_URL,
            query = "Which SleekFlow plan includes a service-level agreement (SLA)?",
            sessionId = QuerySessionId("sf-sla")
        )

        println("=== SLA AVAILABILITY ===")
        printResult(result)

        assertTrue(result.success, "Should find SLA info")
        assertNotNull(result.answer)
        val answer = result.answer!!.lowercase()
        assertTrue(
            answer.contains("enterprise"),
            "SLA should be Enterprise only: ${result.answer}"
        )
    }

    @Test
    fun `support table - dedicated customer success`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = PRICING_URL,
            query = "Does the SleekFlow Premium plan include dedicated customer success and onboarding support, or is it only on the Enterprise plan?",
            sessionId = QuerySessionId("sf-cust-success")
        )

        println("=== DEDICATED CUSTOMER SUCCESS ===")
        printResult(result)

        assertTrue(result.success, "Should find customer success info")
        assertNotNull(result.answer)
        val answer = result.answer!!.lowercase()
        assertTrue(
            answer.contains("enterprise"),
            "Should mention Enterprise for dedicated customer success: ${result.answer}"
        )
        assertTrue(
            answer.contains("not") || answer.contains("only") || answer.contains("enterprise"),
            "Should indicate Premium does NOT include it: ${result.answer}"
        )
    }

    @Test
    fun `support table - group onboarding on Free plan`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = PRICING_URL,
            query = "Is group onboarding support available on the SleekFlow Free plan?",
            sessionId = QuerySessionId("sf-group-onboard")
        )

        println("=== GROUP ONBOARDING - FREE PLAN ===")
        printResult(result)

        assertTrue(result.success, "Should find group onboarding info")
        assertNotNull(result.answer)
        val answer = result.answer!!.lowercase()
        assertTrue(
            answer.contains("not") || answer.contains("no") ||
                    answer.contains("excluded") || answer.contains("paid") ||
                    answer.contains("pro") || answer.contains("premium"),
            "Should indicate Free plan does NOT have group onboarding: ${result.answer}"
        )
    }

    @Test
    fun `support table - business consultancy on Pro`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = PRICING_URL,
            query = "Is the business consultancy service included in the SleekFlow Pro plan, or is it an add-on?",
            sessionId = QuerySessionId("sf-consultancy-pro")
        )

        println("=== CONSULTANCY ON PRO ===")
        printResult(result)

        assertTrue(result.success, "Should find consultancy info")
        assertNotNull(result.answer)
        val answer = result.answer!!.lowercase()
        assertTrue(
            answer.contains("add-on") || answer.contains("addon") || answer.contains("add on"),
            "Business consultancy should be Add-on for Pro: ${result.answer}"
        )
    }

    @Test
    fun `support table - Flow Builder setup across plans`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = PRICING_URL,
            query = "Is Flow Builder and AgentFlow setup included or is it an add-on? Which plans have it?",
            sessionId = QuerySessionId("sf-flow-setup")
        )

        println("=== FLOW BUILDER SETUP ===")
        printResult(result)

        assertTrue(result.success, "Should find Flow Builder setup info")
        assertNotNull(result.answer)
        val answer = result.answer!!.lowercase()
        assertTrue(
            answer.contains("add-on") || answer.contains("addon") || answer.contains("add on"),
            "Flow Builder setup should be an add-on: ${result.answer}"
        )
    }

    // ==================== Group 5: Integrations Section ====================

    @Test
    fun `integrations table - Salesforce availability`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = PRICING_URL,
            query = "Which SleekFlow plans include Salesforce integration (including Marketing Cloud)?",
            sessionId = QuerySessionId("sf-salesforce")
        )

        println("=== SALESFORCE INTEGRATION ===")
        printResult(result)

        assertTrue(result.success, "Should find Salesforce info")
        assertNotNull(result.answer)
        val answer = result.answer!!.lowercase()
        assertTrue(
            answer.contains("enterprise"),
            "Salesforce should be Enterprise only: ${result.answer}"
        )
    }

    // ==================== Group 6: FAQ Accordion (interaction required) ====================

    @Test
    fun `faq accordion - what counts as active contact`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = PRICING_URL,
            query = "According to SleekFlow's FAQ, what counts as a monthly active contact? Give specific examples of what IS and what IS NOT counted.",
            sessionId = QuerySessionId("sf-faq-mac-def")
        )

        println("=== FAQ: WHAT COUNTS AS MAC ===")
        printResult(result)

        assertTrue(result.success, "Should find MAC definition from FAQ")
        assertNotNull(result.answer)
        val answer = result.answer!!.lowercase()
        assertTrue(
            answer.contains("message") || answer.contains("send") ||
                    answer.contains("receive") || answer.contains("broadcast"),
            "MAC definition should mention messaging: ${result.answer}"
        )
    }

    @Test
    fun `faq accordion - Pro vs Premium guidance`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = PRICING_URL,
            query = "According to SleekFlow's FAQ, how should I choose between the Pro and Premium plans?",
            sessionId = QuerySessionId("sf-faq-pro-vs-premium")
        )

        println("=== FAQ: PRO VS PREMIUM ===")
        printResult(result)

        assertTrue(result.success, "Should find Pro vs Premium guidance")
        assertNotNull(result.answer)
        val answer = result.answer!!.lowercase()
        assertTrue(
            answer.contains("pro") && answer.contains("premium"),
            "Answer should discuss both plans: ${result.answer}"
        )
    }

    // ==================== Group 7: Add-on Pricing (deep scroll) ====================

    @Test
    fun `addon pricing - business consultancy monthly price`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = PRICING_URL,
            query = "What is the monthly price of SleekFlow's business consultancy service add-on?",
            sessionId = QuerySessionId("sf-consultancy-price")
        )

        println("=== CONSULTANCY PRICE ===")
        printResult(result)

        assertTrue(result.success, "Should find consultancy price")
        assertNotNull(result.answer)
        assertTrue(
            result.answer!!.contains("499"),
            "Business consultancy should cost US\$499/month: ${result.answer}"
        )
    }

    @Test
    fun `addon pricing - dedicated onboarding one-off price`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = PRICING_URL,
            query = "What is the price for SleekFlow's 1:1 dedicated onboarding support?",
            sessionId = QuerySessionId("sf-onboarding-price")
        )

        println("=== ONBOARDING PRICE ===")
        printResult(result)

        assertTrue(result.success, "Should find onboarding price")
        assertNotNull(result.answer)
        assertTrue(
            result.answer!!.contains("499"),
            "Dedicated onboarding should cost US\$499 one-off: ${result.answer}"
        )
    }

    // ==================== Group 8: AI/Non-AI Toggle (requires clicking toggle) ====================

    @Test
    fun `ai toggle - non-AI Pro plan price`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = PRICING_URL,
            query = "What is the price of the SleekFlow Pro plan without unlimited AI usage?",
            sessionId = QuerySessionId("sf-noai-pro")
        )

        println("=== NON-AI PRO PRICE ===")
        printResult(result)

        assertTrue(result.success, "Should find non-AI Pro plan price")
        assertNotNull(result.answer)
        assertTrue(
            result.answer!!.matches(Regex(".*\\d+.*")),
            "Should report a numeric price: ${result.answer}"
        )
    }

    @Test
    fun `ai toggle - non-AI Premium plan price`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = PRICING_URL,
            query = "What is the price of the SleekFlow Premium plan without unlimited AI?",
            sessionId = QuerySessionId("sf-noai-premium")
        )

        println("=== NON-AI PREMIUM PRICE ===")
        printResult(result)

        assertTrue(result.success, "Should find non-AI Premium plan price")
        assertNotNull(result.answer)
        assertTrue(
            result.answer!!.matches(Regex(".*\\d+.*")),
            "Should report a numeric price: ${result.answer}"
        )
    }

    @Test
    fun `ai toggle - compare AI vs non-AI Pro pricing`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = PRICING_URL,
            query = "What is the price difference between the SleekFlow Pro AI plan and the Pro plan without AI?",
            sessionId = QuerySessionId("sf-ai-vs-noai")
        )

        println("=== AI VS NON-AI PRO COMPARISON ===")
        printResult(result)

        assertTrue(result.success, "Should find pricing comparison")
        assertNotNull(result.answer)
        val answer = result.answer!!.lowercase()
        assertTrue(
            answer.contains("pro"),
            "Should mention the Pro plan: ${result.answer}"
        )
    }

    // ==================== Group 9: Billing Period Toggle ====================

    @Test
    fun `billing toggle - monthly Pro AI price`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = PRICING_URL,
            query = "What is the monthly billed price of the SleekFlow Pro AI plan?",
            sessionId = QuerySessionId("sf-monthly-pro")
        )

        println("=== MONTHLY BILLING PRO AI ===")
        printResult(result)

        assertTrue(result.success, "Should find monthly Pro AI price")
        assertNotNull(result.answer)
        assertTrue(
            result.answer!!.matches(Regex(".*\\d+.*")),
            "Should report a numeric price: ${result.answer}"
        )
    }

    @Test
    fun `billing toggle - yearly Pro AI price`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = PRICING_URL,
            query = "What is the yearly billed price of the SleekFlow Pro AI plan?",
            sessionId = QuerySessionId("sf-yearly-pro")
        )

        println("=== YEARLY BILLING PRO AI ===")
        printResult(result)

        assertTrue(result.success, "Should find yearly Pro AI price")
        assertNotNull(result.answer)
        assertTrue(
            result.answer!!.matches(Regex(".*\\d+.*")),
            "Should report a numeric price: ${result.answer}"
        )
    }

    // ==================== Helpers ====================

    private fun printResult(result: AgenticPageSearchResult) {
        println("Success: ${result.success}")
        println("Answer: ${result.answer}")
        println("Evidence: ${result.evidence}")
        println("Actions (${result.actionsPerformed.size}):")
        result.actionsPerformed.forEachIndexed { idx, action ->
            println("  ${idx + 1}. $action")
        }
        val clickCount = result.actionsPerformed.count { it.action is NavigationAction.Click }
        val findCount = result.actionsPerformed.count { it.action is NavigationAction.FindOnPage }
        val scrollToTextCount = result.actionsPerformed.count { it.action is NavigationAction.ScrollToText }
        val scrollCount = result.actionsPerformed.count { it.action is NavigationAction.Scroll }
        println("Clicks: $clickCount, Finds: $findCount, ScrollToText: $scrollToTextCount, Scrolls: $scrollCount")
        println("Token usage: prompt=${result.totalTokenUsage.promptTokens}, " +
                "output=${result.totalTokenUsage.outputTokens}, " +
                "total=${result.totalTokenUsage.totalTokens}")

        val report = ActionEfficiencyAnalyzer.analyze(result, result.actionsPerformed.size)
        println("\n--- Efficiency Summary ---")
        println("find_on_page: ${report.findOnPageCount} | scroll_to_text: ${report.scrollToTextCount} | scroll: ${report.scrollCount}")
    }
}
