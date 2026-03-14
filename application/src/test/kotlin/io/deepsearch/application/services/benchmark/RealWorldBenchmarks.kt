package io.deepsearch.application.services.benchmark

import io.deepsearch.domain.agents.NavigationAction

/**
 * Benchmark case definitions for real-world pages.
 *
 * These have wider constraint tolerances than controlled pages because:
 * - Live pages can change layout, prices, and content over time
 * - Cookie banners and dynamic content may add 1-2 extra iterations
 * - The VLM must handle real-world visual complexity
 *
 * Ideal sequences were determined by browsing the live pages (March 2026).
 * Prices and content should be verified periodically.
 */
object RealWorldBenchmarks {

    fun all(): List<BenchmarkCase> = sleekFlow() + otpHealthcare()

    fun sleekFlow(): List<BenchmarkCase> = listOf(
        sfProAiPrice(),
        sfPremiumAiPrice(),
        sfRoleBasedAccessControl(),
        sfFaqActiveContact(),
        sfMonthlyActiveContacts(),
        sfSalesforceIntegration()
    )

    fun otpHealthcare(): List<BenchmarkCase> = listOf(
        otpStandardPrice(),
        otpUltraPrice(),
        otpStressTest(),
        otpCardiovascularPrice(),
        otpWellWomanGoldPrice()
    )

    // ==================== SleekFlow Pricing ====================

    /**
     * Pro AI price is near the top of the page (1-2 scrolls).
     * Ideal: scroll slightly or read directly, then answer.
     * Price visible: ~1 scroll from top.
     */
    private fun sfProAiPrice() = BenchmarkCase(
        id = "sf-pro-ai-price",
        description = "SleekFlow Pro AI plan price (near top)",
        pageSource = PageSource.Url("https://sleekflow.io/pricing"),
        query = "What is the price of the SleekFlow Pro AI plan?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("99")),
        idealActionSequence = listOf(
            NavigationAction.FindOnPage::class,
            NavigationAction.ScrollToText::class,
            NavigationAction.AnswerFound::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 7
        )
    )

    /**
     * Premium AI price requires scrolling further down past Pro card.
     * Ideal: scroll or scroll_to_text("Premium"), then answer.
     */
    private fun sfPremiumAiPrice() = BenchmarkCase(
        id = "sf-premium-ai-price",
        description = "SleekFlow Premium AI plan price (2-3 scrolls)",
        pageSource = PageSource.Url("https://sleekflow.io/pricing"),
        query = "What is the price of the SleekFlow Premium AI plan?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("299")),
        idealActionSequence = listOf(
            NavigationAction.FindOnPage::class,
            NavigationAction.ScrollToText::class,
            NavigationAction.AnswerFound::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
    )

    /**
     * Role-based access control is in the feature comparison grid (~4 scrolls down).
     * Ideal: scroll_to_text("role-based") to jump directly, then answer.
     */
    private fun sfRoleBasedAccessControl() = BenchmarkCase(
        id = "sf-rbac",
        description = "SleekFlow RBAC plan availability (deep in feature grid)",
        pageSource = PageSource.Url("https://sleekflow.io/pricing"),
        query = "Which SleekFlow plans include role-based access control?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("premium", "enterprise"), caseSensitive = false),
        idealActionSequence = listOf(
            NavigationAction.FindOnPage::class,
            NavigationAction.ScrollToText::class,
            NavigationAction.AnswerFound::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 10
        )
    )

    /**
     * FAQ accordion: "What counts as an active contact?"
     * FAQ is ~9-10 scrolls down. Must scroll to FAQ, click accordion, read.
     * Ideal: scroll_to_text("Frequently Asked") -> click accordion -> answer.
     */
    private fun sfFaqActiveContact() = BenchmarkCase(
        id = "sf-faq-active-contact",
        description = "SleekFlow FAQ: what counts as active contact (accordion)",
        pageSource = PageSource.Url("https://sleekflow.io/pricing"),
        query = "According to SleekFlow's FAQ, what counts as a monthly active contact?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("message"), caseSensitive = false),
        idealActionSequence = listOf(
            NavigationAction.FindOnPage::class,
            NavigationAction.ScrollToText::class,
            NavigationAction.Click::class,
            NavigationAction.AnswerFound::class
        ),
        optimalIterations = 4,
        constraints = BenchmarkConstraints(
            maxIterations = 10
        )
    )

    /**
     * Monthly active contacts per plan is in the omnichannel feature grid.
     * Ideal: scroll_to_text("monthly active contacts") -> read table -> answer.
     */
    private fun sfMonthlyActiveContacts() = BenchmarkCase(
        id = "sf-macs-per-plan",
        description = "SleekFlow MACs per plan (feature comparison grid)",
        pageSource = PageSource.Url("https://sleekflow.io/pricing"),
        query = "How many monthly active contacts are included in each SleekFlow plan?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("50", "500"), caseSensitive = false),
        idealActionSequence = listOf(
            NavigationAction.FindOnPage::class,
            NavigationAction.ScrollToText::class,
            NavigationAction.AnswerFound::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 9
        )
    )

    /**
     * Salesforce integration is deep in the integrations section of the feature grid.
     * Ideal: scroll_to_text("Salesforce") -> read -> answer.
     */
    private fun sfSalesforceIntegration() = BenchmarkCase(
        id = "sf-salesforce",
        description = "SleekFlow Salesforce integration availability",
        pageSource = PageSource.Url("https://sleekflow.io/pricing"),
        query = "Which SleekFlow plans include Salesforce integration?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("enterprise"), caseSensitive = false),
        idealActionSequence = listOf(
            NavigationAction.FindOnPage::class,
            NavigationAction.ScrollToText::class,
            NavigationAction.AnswerFound::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 9
        )
    )

    // ==================== OT&P Healthcare ====================

    /**
     * Standard package price is in the comparison table (~2-3 viewports down).
     * Ideal: scroll_to_text("Compare") or scroll_to_text("Standard") -> read table -> answer.
     */
    private fun otpStandardPrice() = BenchmarkCase(
        id = "otp-standard-price",
        description = "OT&P Standard body check price",
        pageSource = PageSource.Url("https://www.otandp.com/body-check/"),
        query = "What is the price of the OT&P Standard body check package?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("5,900")),
        idealActionSequence = listOf(
            NavigationAction.FindOnPage::class,
            NavigationAction.ScrollToText::class,
            NavigationAction.AnswerFound::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 9
        )
    )

    /**
     * Ultra package price is in the same comparison table.
     * Ideal: scroll_to_text("Ultra") -> read -> answer.
     */
    private fun otpUltraPrice() = BenchmarkCase(
        id = "otp-ultra-price",
        description = "OT&P Ultra body check price",
        pageSource = PageSource.Url("https://www.otandp.com/body-check/"),
        query = "What is the price of the OT&P Ultra body check package?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("15,900")),
        idealActionSequence = listOf(
            NavigationAction.FindOnPage::class,
            NavigationAction.ScrollToText::class,
            NavigationAction.AnswerFound::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 9
        )
    )

    /**
     * Stress Test inclusion requires reading the feature comparison table.
     * Ideal: scroll_to_text("Stress Test") -> read which columns have it -> answer.
     */
    private fun otpStressTest() = BenchmarkCase(
        id = "otp-stress-test",
        description = "OT&P which package includes Stress Test",
        pageSource = PageSource.Url("https://www.otandp.com/body-check/"),
        query = "Which OT&P body check package includes a Stress Test (treadmill test)?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("ultra"), caseSensitive = false),
        idealActionSequence = listOf(
            NavigationAction.FindOnPage::class,
            NavigationAction.ScrollToText::class,
            NavigationAction.AnswerFound::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 9
        )
    )

    /**
     * Cardiovascular Risk Package is on the specialised health checks page.
     * Price is visible after modest scrolling (~1-2 viewports).
     */
    private fun otpCardiovascularPrice() = BenchmarkCase(
        id = "otp-cardiovascular-price",
        description = "OT&P Cardiovascular Risk Package price (specialised page)",
        pageSource = PageSource.Url("https://www.otandp.com/body-check/specialised-health-checks"),
        query = "What is the price of the OT&P Cardiovascular Risk Package?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("4,900")),
        idealActionSequence = listOf(
            NavigationAction.FindOnPage::class,
            NavigationAction.ScrollToText::class,
            NavigationAction.AnswerFound::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 9
        )
    )

    /**
     * Well Woman Gold price is in the Well Woman comparison table, further down
     * the specialised page. Requires scrolling past the three main packages.
     */
    private fun otpWellWomanGoldPrice() = BenchmarkCase(
        id = "otp-wellwoman-gold",
        description = "OT&P Well Woman Gold package price",
        pageSource = PageSource.Url("https://www.otandp.com/body-check/specialised-health-checks"),
        query = "What is the price of the OT&P Well Woman Gold package?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("9,900")),
        idealActionSequence = listOf(
            NavigationAction.FindOnPage::class,
            NavigationAction.ScrollToText::class,
            NavigationAction.AnswerFound::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 9
        )
    )
}
