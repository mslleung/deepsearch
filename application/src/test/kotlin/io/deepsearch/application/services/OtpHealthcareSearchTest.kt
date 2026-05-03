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

    private suspend fun runTest(
        testName: String,
        url: String,
        query: String,
        expectedAnswerCheck: (String) -> Unit
    ) {
        println("\n==================================================")
        println("TEST: $testName")
        println("URL: $url")
        println("QUERY: $query")
        println("==================================================\n")

        val start = System.currentTimeMillis()
        val result = agenticSearchService.searchWithinPage(
            url = url,
            query = query,
            sessionId = QuerySessionId("fp-$testName")
        )
        val latency = System.currentTimeMillis() - start
        printResult(result, latency)

        assertNotNull(result.answer, "Answer should not be null")
        expectedAnswerCheck(result.answer!!)
    }

    // ==================== Group 1: Body Check Package Pricing ====================

    @Test
    fun `body check - Standard package price`() = runBlocking {
        runTest(
            testName = "Standard package price",
            url = BODY_CHECK_URL,
            query = "What is the price of the OT&P Standard body check package?"
        ) { answer ->
            assertTrue(answer.contains("5,900") || answer.contains("5900"), "Failed: $answer")
        }
    }

    @Test
    fun `body check - Ultra package price`() = runBlocking {
        runTest(
            testName = "Ultra package price",
            url = BODY_CHECK_URL,
            query = "What is the price of the OT&P Ultra body check package?"
        ) { answer ->
            assertTrue(answer.contains("15,900") || answer.contains("15900"), "Failed: $answer")
        }
    }

    @Test
    fun `body check - all package prices`() = runBlocking {
        runTest(
            testName = "All package prices",
            url = BODY_CHECK_URL,
            query = "List all OT&P body check packages and their prices."
        ) { answer ->
            assertTrue(answer.contains("5,900") || answer.contains("5900"), "Failed: $answer")
            assertTrue(answer.contains("9,900") || answer.contains("9900"), "Failed: $answer")
            assertTrue(answer.contains("15,900") || answer.contains("15900"), "Failed: $answer")
        }
    }

    // ==================== Group 2: Body Check Feature Comparison (table reading) ====================

    @Test
    fun `body check - which package includes Stress Test`() = runBlocking {
        runTest(
            testName = "Stress Test inclusion",
            url = BODY_CHECK_URL,
            query = "Which OT&P body check package includes a Stress Test (treadmill test)?"
        ) { answer ->
            assertTrue(answer.lowercase().contains("ultra"), "Failed: $answer")
        }
    }

    @Test
    fun `body check - consultation duration for Standard vs Comprehensive`() = runBlocking {
        runTest(
            testName = "Consultation duration",
            url = BODY_CHECK_URL,
            query = "What is the initial consultation duration for the OT&P Standard body check package compared to the Comprehensive package?"
        ) { answer ->
            assertTrue(answer.contains("45") && answer.contains("60"), "Failed: $answer")
        }
    }

    @Test
    fun `body check - ECG included in all packages`() = runBlocking {
        runTest(
            testName = "ECG inclusion",
            url = BODY_CHECK_URL,
            query = "Is ECG included in all OT&P body check packages, or only in certain ones?"
        ) { answer ->
            val lower = answer.lowercase()
            assertTrue(lower.contains("all") || lower.contains("every") || lower.contains("each") || (lower.contains("standard") && lower.contains("comprehensive") && lower.contains("ultra")), "Failed: $answer")
        }
    }

    // ==================== Group 3: Specialised Health Checks (separate page) ====================

    @Test
    fun `specialised - Cardiovascular Risk Package price`() = runBlocking {
        runTest(
            testName = "Cardiovascular Risk price",
            url = SPECIALISED_URL,
            query = "What is the price of the OT&P Cardiovascular Risk Package?"
        ) { answer ->
            assertTrue(answer.contains("4,900") || answer.contains("4900"), "Failed: $answer")
        }
    }

    @Test
    fun `specialised - Cancer Risk Package contents`() = runBlocking {
        runTest(
            testName = "Cancer Risk contents",
            url = SPECIALISED_URL,
            query = "What tests are included in the OT&P Cancer Risk Package? List the specific tests."
        ) { answer ->
            val lower = answer.lowercase()
            assertTrue(lower.contains("ultrasound") && (lower.contains("cea") || lower.contains("carcinoembryonic")), "Failed: $answer")
        }
    }

    @Test
    fun `specialised - all package prices comparison`() = runBlocking {
        runTest(
            testName = "All specialised prices",
            url = SPECIALISED_URL,
            query = "List all specialised health check packages offered by OT&P and their prices."
        ) { answer ->
            assertTrue(answer.contains("4,900") || answer.contains("4900"), "Failed: $answer")
            assertTrue(answer.contains("7,900") || answer.contains("7900"), "Failed: $answer")
            assertTrue(answer.contains("6,900") || answer.contains("6900"), "Failed: $answer")
        }
    }

    // ==================== Group 4: Well Woman Packages (tiered comparison table) ====================

    @Test
    fun `well woman - Gold package price`() = runBlocking {
        runTest(
            testName = "Well Woman Gold price",
            url = SPECIALISED_URL,
            query = "What is the price of the OT&P Well Woman Gold package?"
        ) { answer ->
            assertTrue(answer.contains("9,900") || answer.contains("9900"), "Failed: $answer")
        }
    }

    @Test
    fun `well woman - Bronze includes Pap Smear but not Mammogram`() = runBlocking {
        runTest(
            testName = "Well Woman Bronze features",
            url = SPECIALISED_URL,
            query = "Does the OT&P Well Woman Bronze package include a 3D Mammogram? What about a Pap Smear?"
        ) { answer ->
            val lower = answer.lowercase()
            assertTrue((lower.contains("pap") || lower.contains("smear")) && (lower.contains("mammogram") || lower.contains("3d")), "Failed: $answer")
        }
    }

    @Test
    fun `well woman - difference between Silver and Bronze`() = runBlocking {
        runTest(
            testName = "Well Woman Silver vs Bronze",
            url = SPECIALISED_URL,
            query = "What tests are included in the OT&P Well Woman Silver package that are not in the Bronze package?"
        ) { answer ->
            val lower = answer.lowercase()
            assertTrue(lower.contains("hpv") || lower.contains("pelvic") || lower.contains("ultrasound"), "Failed: $answer")
        }
    }

    // ==================== Group 5: FAQ Accordion (interaction required) ====================

    @Test
    fun `faq - waiting list to register`() = runBlocking {
        runTest(
            testName = "FAQ waiting list",
            url = FAQ_URL,
            query = "Is there a waiting list to register with OT&P medical practice?"
        ) { answer ->
            val lower = answer.lowercase()
            assertTrue(lower.contains("waiting list") || lower.contains("register"), "Failed: $answer")
        }
    }

    @Test
    fun `faq - consultation duration`() = runBlocking {
        runTest(
            testName = "FAQ consultation duration",
            url = FAQ_URL,
            query = "How long are first-time GP consultations at OT&P?"
        ) { answer ->
            val lower = answer.lowercase()
            assertTrue(lower.contains("consultation") || lower.contains("minute"), "Failed: $answer")
        }
    }

    @Test
    fun `faq - clinic locations count`() = runBlocking {
        runTest(
            testName = "FAQ clinic locations",
            url = FAQ_URL,
            query = "How many clinics does OT&P have in Hong Kong?"
        ) { answer ->
            val lower = answer.lowercase()
            assertTrue(lower.contains("clinic") || lower.contains("location"), "Failed: $answer")
        }
    }

    @Test
    fun `faq - insurance direct billing`() = runBlocking {
        runTest(
            testName = "FAQ insurance billing",
            url = FAQ_URL,
            query = "Does OT&P have direct billing with insurance companies?"
        ) { answer ->
            val lower = answer.lowercase()
            assertTrue(lower.contains("insurance") || lower.contains("billing") || lower.contains("direct"), "Failed: $answer")
        }
    }

    // ==================== Helpers ====================

    private fun printResult(result: AgenticPageSearchResult, latencyMs: Long) {
        println("Success: ${result.success}")
        println("Latency: ${latencyMs}ms")
        println("Answer: ${result.answer}")
        println("Evidence: ${result.evidence}")
        println("Actions (${result.actionsPerformed.size}):")
        result.actionsPerformed.forEachIndexed { idx, action ->
            println("  ${idx + 1}. $action")
        }
        val clickCount = result.actionsPerformed.count { it.action is NavigationAction.Click }
        val scrollAtCount = result.actionsPerformed.count { it.action is NavigationAction.ScrollAt }
        println("Clicks: $clickCount, ScrollAt: $scrollAtCount")
        println("Token usage: prompt=${result.totalTokenUsage.promptTokens}, " +
                "output=${result.totalTokenUsage.outputTokens}, " +
                "total=${result.totalTokenUsage.totalTokens}")
    }
}
