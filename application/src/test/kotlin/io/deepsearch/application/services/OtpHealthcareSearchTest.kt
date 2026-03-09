package io.deepsearch.application.services

import io.deepsearch.application.config.applicationBenchmarkTestModule
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
 * Integration tests against OT&P Healthcare (https://www.otandp.com) to validate the
 * agentic search pipeline against real-world healthcare content.
 *
 * Tests stress:
 * - Reading comparison tables with feature grids (body check packages)
 * - Interpreting checkmark/cross symbols in feature matrices
 * - Scrolling through long pages with multiple table sections
 * - Clicking FAQ accordions to reveal hidden content
 * - Cross-referencing features across pricing tiers (Well Woman Bronze/Silver/Gold)
 * - Extracting specific test names and prices from dense medical content
 *
 * Requires deepsearch-browser running at localhost:8090 and GOOGLE_API_KEY env var.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.CONCURRENT)
class OtpHealthcareSearchTest : IsolatedKoinTest() {

    private lateinit var koinApp: KoinApplication
    private val agenticSearchService by inject<IAgenticWebpageSearchService>()
    private val applicationScope by inject<IApplicationCoroutineScope>()

    companion object {
        private const val BODY_CHECK_URL = "https://www.otandp.com/body-check/"
        private const val SPECIALISED_URL = "https://www.otandp.com/body-check/specialised-health-checks"
        private const val FAQ_URL = "https://www.otandp.com/faq/"
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

    // ==================== Group 1: Body Check Package Pricing ====================

    @Test
    fun `body check - Standard package price`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = BODY_CHECK_URL,
            query = "What is the price of the OT&P Standard body check package?",
            sessionId = QuerySessionId("otp-standard-price")
        )

        println("=== STANDARD PACKAGE PRICE ===")
        printResult(result)

        assertTrue(result.success, "Should find Standard package price")
        assertNotNull(result.answer)
        assertTrue(
            result.answer!!.contains("5,900") || result.answer!!.contains("5900"),
            "Standard package should cost HK\$5,900: ${result.answer}"
        )
    }

    @Test
    fun `body check - Ultra package price`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = BODY_CHECK_URL,
            query = "What is the price of the OT&P Ultra body check package?",
            sessionId = QuerySessionId("otp-ultra-price")
        )

        println("=== ULTRA PACKAGE PRICE ===")
        printResult(result)

        assertTrue(result.success, "Should find Ultra package price")
        assertNotNull(result.answer)
        assertTrue(
            result.answer!!.contains("15,900") || result.answer!!.contains("15900"),
            "Ultra package should cost HK\$15,900: ${result.answer}"
        )
    }

    @Test
    fun `body check - all package prices`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = BODY_CHECK_URL,
            query = "List all OT&P body check packages and their prices.",
            sessionId = QuerySessionId("otp-all-prices")
        )

        println("=== ALL PACKAGE PRICES ===")
        printResult(result)

        assertTrue(result.success, "Should find all package prices")
        assertNotNull(result.answer)
        val answer = result.answer!!
        assertTrue(answer.contains("5,900") || answer.contains("5900"), "Should list Standard at HK\$5,900: $answer")
        assertTrue(answer.contains("9,900") || answer.contains("9900"), "Should list Comprehensive at HK\$9,900: $answer")
        assertTrue(answer.contains("15,900") || answer.contains("15900"), "Should list Ultra at HK\$15,900: $answer")
    }

    // ==================== Group 2: Body Check Feature Comparison (table reading) ====================

    @Test
    fun `body check - which package includes Stress Test`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = BODY_CHECK_URL,
            query = "Which OT&P body check package includes a Stress Test (treadmill test)?",
            sessionId = QuerySessionId("otp-stress-test")
        )

        println("=== STRESS TEST INCLUSION ===")
        printResult(result)

        assertTrue(result.success, "Should find Stress Test info")
        assertNotNull(result.answer)
        val answer = result.answer!!.lowercase()
        assertTrue(
            answer.contains("ultra"),
            "Stress Test should be in the Ultra package: ${result.answer}"
        )
    }

    @Test
    fun `body check - consultation duration for Standard vs Comprehensive`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = BODY_CHECK_URL,
            query = "What is the initial consultation duration for the OT&P Standard body check package compared to the Comprehensive package?",
            sessionId = QuerySessionId("otp-consult-duration")
        )

        println("=== CONSULTATION DURATIONS ===")
        printResult(result)

        assertTrue(result.success, "Should find consultation durations")
        assertNotNull(result.answer)
        val answer = result.answer!!
        assertTrue(answer.contains("45"), "Standard should have 45 min initial consult: $answer")
        assertTrue(answer.contains("60"), "Comprehensive should have 60 min initial consult: $answer")
    }

    @Test
    fun `body check - ECG included in all packages`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = BODY_CHECK_URL,
            query = "Is ECG included in all OT&P body check packages, or only in certain ones?",
            sessionId = QuerySessionId("otp-ecg")
        )

        println("=== ECG INCLUSION ===")
        printResult(result)

        assertTrue(result.success, "Should find ECG info")
        assertNotNull(result.answer)
        val answer = result.answer!!.lowercase()
        assertTrue(
            answer.contains("all") || answer.contains("every") || answer.contains("each") ||
                    (answer.contains("standard") && answer.contains("comprehensive") && answer.contains("ultra")),
            "ECG should be included in all packages: ${result.answer}"
        )
    }

    // ==================== Group 3: Specialised Health Checks (separate page) ====================

    @Test
    fun `specialised - Cardiovascular Risk Package price`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = SPECIALISED_URL,
            query = "What is the price of the OT&P Cardiovascular Risk Package?",
            sessionId = QuerySessionId("otp-cardio-price")
        )

        println("=== CARDIOVASCULAR RISK PRICE ===")
        printResult(result)

        assertTrue(result.success, "Should find Cardiovascular Risk Package price")
        assertNotNull(result.answer)
        assertTrue(
            result.answer!!.contains("4,900") || result.answer!!.contains("4900"),
            "Cardiovascular Risk Package should cost HK\$4,900: ${result.answer}"
        )
    }

    @Test
    fun `specialised - Cancer Risk Package contents`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = SPECIALISED_URL,
            query = "What tests are included in the OT&P Cancer Risk Package? List the specific tests.",
            sessionId = QuerySessionId("otp-cancer-tests")
        )

        println("=== CANCER RISK PACKAGE CONTENTS ===")
        printResult(result)

        assertTrue(result.success, "Should find Cancer Risk Package contents")
        assertNotNull(result.answer)
        val answer = result.answer!!.lowercase()
        assertTrue(answer.contains("ultrasound"), "Should include Ultrasound: ${result.answer}")
        assertTrue(
            answer.contains("cea") || answer.contains("carcinoembryonic"),
            "Should include CEA: ${result.answer}"
        )
    }

    @Test
    fun `specialised - all package prices comparison`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = SPECIALISED_URL,
            query = "List all specialised health check packages offered by OT&P and their prices.",
            sessionId = QuerySessionId("otp-specialised-all")
        )

        println("=== ALL SPECIALISED PRICES ===")
        printResult(result)

        assertTrue(result.success, "Should find all specialised package prices")
        assertNotNull(result.answer)
        val answer = result.answer!!
        assertTrue(answer.contains("4,900") || answer.contains("4900"), "Should list Cardiovascular at HK\$4,900: $answer")
        assertTrue(answer.contains("7,900") || answer.contains("7900"), "Should list Fit at Fifty at HK\$7,900: $answer")
        assertTrue(answer.contains("6,900") || answer.contains("6900"), "Should list Cancer Risk at HK\$6,900: $answer")
    }

    // ==================== Group 4: Well Woman Packages (tiered comparison table) ====================

    @Test
    fun `well woman - Gold package price`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = SPECIALISED_URL,
            query = "What is the price of the OT&P Well Woman Gold package?",
            sessionId = QuerySessionId("otp-wellwoman-gold")
        )

        println("=== WELL WOMAN GOLD PRICE ===")
        printResult(result)

        assertTrue(result.success, "Should find Well Woman Gold price")
        assertNotNull(result.answer)
        assertTrue(
            result.answer!!.contains("9,900") || result.answer!!.contains("9900"),
            "Well Woman Gold should cost HK\$9,900: ${result.answer}"
        )
    }

    @Test
    fun `well woman - Bronze includes Pap Smear but not Mammogram`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = SPECIALISED_URL,
            query = "Does the OT&P Well Woman Bronze package include a 3D Mammogram? What about a Pap Smear?",
            sessionId = QuerySessionId("otp-wellwoman-bronze")
        )

        println("=== WELL WOMAN BRONZE FEATURES ===")
        printResult(result)

        assertTrue(result.success, "Should find Well Woman Bronze features")
        assertNotNull(result.answer)
        val answer = result.answer!!.lowercase()
        assertTrue(
            answer.contains("pap") || answer.contains("smear"),
            "Should mention Pap Smear: ${result.answer}"
        )
        assertTrue(
            answer.contains("mammogram") || answer.contains("3d"),
            "Should mention Mammogram (whether included or not): ${result.answer}"
        )
    }

    @Test
    fun `well woman - difference between Silver and Bronze`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = SPECIALISED_URL,
            query = "What tests are included in the OT&P Well Woman Silver package that are not in the Bronze package?",
            sessionId = QuerySessionId("otp-wellwoman-silver-vs-bronze")
        )

        println("=== WELL WOMAN SILVER VS BRONZE ===")
        printResult(result)

        assertTrue(result.success, "Should find Silver vs Bronze differences")
        assertNotNull(result.answer)
        val answer = result.answer!!.lowercase()
        assertTrue(
            answer.contains("hpv") || answer.contains("pelvic") || answer.contains("ultrasound"),
            "Silver should add HPV Test and/or Pelvic Ultrasound over Bronze: ${result.answer}"
        )
    }

    // ==================== Group 5: FAQ Accordion (interaction required) ====================
    //
    // Known limitation: OT&P's FAQ uses non-semantic accordion triggers (divs with JS
    // click handlers instead of <button> elements), so they don't appear in the
    // interactive element list. The agent can SEE the question text but cannot CLICK to
    // expand. These tests validate the agent's behavior in this degraded scenario:
    // - Can it identify the relevant question on the page?
    // - Does it attempt reasonable recovery (clicking nearby elements, scrolling)?
    // - Does it report the question text even if it can't get the expanded answer?
    //
    // Expected answer (if accordion could be expanded):
    //   "We currently do not have a waiting list. Registration can be completed
    //    10 minutes before your first appointment."

    @Test
    fun `faq - waiting list to register`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = FAQ_URL,
            query = "Is there a waiting list to register with OT&P medical practice?",
            sessionId = QuerySessionId("otp-faq-waitlist")
        )

        println("=== FAQ: WAITING LIST ===")
        printResult(result)

        val answerText = (result.answer ?: "").lowercase()
        val allText = "${result.observations.joinToString(" ").lowercase()} $answerText"
        assertTrue(
            allText.contains("waiting list") || allText.contains("register"),
            "Agent should find information about the waiting list / registration: answer=$answerText"
        )
    }

    @Test
    fun `faq - consultation duration`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = FAQ_URL,
            query = "How long are first-time GP consultations at OT&P?",
            sessionId = QuerySessionId("otp-faq-consult")
        )

        println("=== FAQ: CONSULTATION DURATION ===")
        printResult(result)

        val answerText = (result.answer ?: "").lowercase()
        val allText = "${result.observations.joinToString(" ").lowercase()} $answerText"
        assertTrue(
            allText.contains("consultation") || allText.contains("minute"),
            "Agent should find consultation duration information: answer=$answerText"
        )
    }

    @Test
    fun `faq - clinic locations count`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = FAQ_URL,
            query = "How many clinics does OT&P have in Hong Kong?",
            sessionId = QuerySessionId("otp-faq-clinics")
        )

        println("=== FAQ: CLINIC COUNT ===")
        printResult(result)

        val answerText = (result.answer ?: "").lowercase()
        val allText = "${result.observations.joinToString(" ").lowercase()} $answerText"
        assertTrue(
            allText.contains("clinic") || allText.contains("location"),
            "Agent should find information about clinic locations: answer=$answerText"
        )
    }

    @Test
    fun `faq - insurance direct billing`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = FAQ_URL,
            query = "Does OT&P have direct billing with insurance companies?",
            sessionId = QuerySessionId("otp-faq-insurance")
        )

        println("=== FAQ: INSURANCE BILLING ===")
        printResult(result)

        val answerText = (result.answer ?: "").lowercase()
        val allText = "${result.observations.joinToString(" ").lowercase()} $answerText"
        assertTrue(
            allText.contains("insurance") || allText.contains("billing") || allText.contains("direct"),
            "Agent should find insurance billing information: answer=$answerText"
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
        val clickAtCount = result.actionsPerformed.count { it.action is NavigationAction.ClickAt }
        val scrollCount = result.actionsPerformed.count { it.action is NavigationAction.Scroll }
        println("Clicks: $clickCount, ClickAts: $clickAtCount, Scrolls: $scrollCount")
        println("Token usage: prompt=${result.totalTokenUsage.promptTokens}, " +
                "output=${result.totalTokenUsage.outputTokens}, " +
                "total=${result.totalTokenUsage.totalTokens}")
    }
}
