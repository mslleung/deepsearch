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
        otpWellWomanGoldPrice(),
        otpHepCAccordion(),
        otpHomocysteineAccordion(),
        otpFaqHealthCheckDefinition(),
        otpFitAtFiftyPrice(),
        otpCancerRiskPrice(),
        otpCardiovascularStressTest(),
        otpFaqFirstVisitDuration(),
        otpFaqFemaleDoctors(),
        otpCentralClinicAddress(),
        otpAboutFounded()
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
            NavigationAction.ExplorationFinished::class
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
            NavigationAction.ExplorationFinished::class
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
            NavigationAction.ExplorationFinished::class
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
            NavigationAction.ExplorationFinished::class
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
            NavigationAction.ExplorationFinished::class
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
            NavigationAction.ExplorationFinished::class
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
            NavigationAction.ExplorationFinished::class
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
            NavigationAction.ExplorationFinished::class
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
            NavigationAction.ExplorationFinished::class
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
            NavigationAction.ExplorationFinished::class
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
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 9
        )
    )

    // ---- /body-check/ accordion content (hidden until expanded) ----

    /**
     * Hep C Ab is buried inside the "Blood Tests and Investigations" accordion,
     * under the "Infection Profile" sub-section. Only the Ultra column has a check.
     * Ideal: scroll_to_text("Blood Tests") -> click accordion -> scroll_to_text("Hep C") -> answer.
     */
    private fun otpHepCAccordion() = BenchmarkCase(
        id = "otp-hep-c-accordion",
        description = "OT&P Hep C Ab inclusion (deep accordion table)",
        pageSource = PageSource.Url("https://www.otandp.com/body-check/"),
        query = "Which OT&P body check packages include Hepatitis C Antibody (Hep C Ab) testing?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("ultra"), caseSensitive = false),
        idealActionSequence = listOf(
            NavigationAction.FindOnPage::class,
            NavigationAction.ScrollToText::class,
            NavigationAction.Click::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 4,
        constraints = BenchmarkConstraints(
            maxIterations = 12
        )
    )

    /**
     * Homocysteine is under the "Cardiovascular" sub-section inside the
     * "Blood Tests and Investigations" accordion. Only Ultra and Ultra Follow Up
     * have it. Must expand the accordion to see the nested comparison rows.
     * Ideal: scroll_to_text("Blood Tests") -> click accordion -> scroll_to_text("Homocysteine") -> answer.
     */
    private fun otpHomocysteineAccordion() = BenchmarkCase(
        id = "otp-homocysteine-accordion",
        description = "OT&P Homocysteine inclusion (accordion cardiovascular sub-section)",
        pageSource = PageSource.Url("https://www.otandp.com/body-check/"),
        query = "Which OT&P body check packages include Homocysteine testing?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("ultra"), caseSensitive = false),
        idealActionSequence = listOf(
            NavigationAction.FindOnPage::class,
            NavigationAction.ScrollToText::class,
            NavigationAction.Click::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 4,
        constraints = BenchmarkConstraints(
            maxIterations = 12
        )
    )

    /**
     * The FAQ section at the bottom of /body-check/ uses accordions that must
     * be clicked to reveal answers. The first FAQ "What is a health check?"
     * contains a definition mentioning "comprehensive evaluation".
     * Ideal: scroll_to_text("FAQ") -> click accordion heading -> read answer -> answer.
     */
    private fun otpFaqHealthCheckDefinition() = BenchmarkCase(
        id = "otp-faq-health-check-definition",
        description = "OT&P body-check FAQ: what is a health check (accordion)",
        pageSource = PageSource.Url("https://www.otandp.com/body-check/"),
        query = "According to OT&P's body check page FAQ, what is a health check?",
        expectedOutcome = ExpectedOutcome.AnswerContains(
            listOf("comprehensive evaluation"), caseSensitive = false
        ),
        idealActionSequence = listOf(
            NavigationAction.FindOnPage::class,
            NavigationAction.ScrollToText::class,
            NavigationAction.Click::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 4,
        constraints = BenchmarkConstraints(
            maxIterations = 12
        )
    )

    // ---- /body-check/specialised-health-checks tabbed content ----

    /**
     * Fit at Fifty price ($7,900) is only visible after clicking the "Fit at Fifty"
     * tab in the package tab strip. The default active tab is Cardiovascular.
     * Ideal: scroll_to_text("Fit at Fifty") -> click tab -> read price -> answer.
     */
    private fun otpFitAtFiftyPrice() = BenchmarkCase(
        id = "otp-fit-at-fifty-price",
        description = "OT&P Fit at Fifty price (hidden tab)",
        pageSource = PageSource.Url("https://www.otandp.com/body-check/specialised-health-checks"),
        query = "What is the price of the OT&P Fit at Fifty package?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("7,900")),
        idealActionSequence = listOf(
            NavigationAction.FindOnPage::class,
            NavigationAction.ScrollToText::class,
            NavigationAction.Click::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 4,
        constraints = BenchmarkConstraints(
            maxIterations = 11
        )
    )

    /**
     * Cancer Risk Package price ($6,900) is behind the "Cancer Risk" tab,
     * which is not the default selection.
     * Ideal: scroll_to_text("Cancer Risk") -> click tab -> read price -> answer.
     */
    private fun otpCancerRiskPrice() = BenchmarkCase(
        id = "otp-cancer-risk-price",
        description = "OT&P Cancer Risk Package price (hidden tab)",
        pageSource = PageSource.Url("https://www.otandp.com/body-check/specialised-health-checks"),
        query = "What is the price of the OT&P Cancer Risk Package?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("6,900")),
        idealActionSequence = listOf(
            NavigationAction.FindOnPage::class,
            NavigationAction.ScrollToText::class,
            NavigationAction.Click::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 4,
        constraints = BenchmarkConstraints(
            maxIterations = 11
        )
    )

    /**
     * The Cardiovascular Risk tab lists included tests. "Stress Test (Treadmill)"
     * is among them. This may already be the default active tab, but the agent
     * still needs to scroll to the tab content area and read the included items.
     * Ideal: scroll_to_text("Cardiovascular") -> click tab (if needed) -> read list -> answer.
     */
    private fun otpCardiovascularStressTest() = BenchmarkCase(
        id = "otp-cardiovascular-stress-test",
        description = "OT&P Cardiovascular Risk Package includes Stress Test (tab content)",
        pageSource = PageSource.Url("https://www.otandp.com/body-check/specialised-health-checks"),
        query = "Does the OT&P Cardiovascular Risk Package include a Stress Test?",
        expectedOutcome = ExpectedOutcome.AnswerContains(
            listOf("stress test", "treadmill"), caseSensitive = false
        ),
        idealActionSequence = listOf(
            NavigationAction.FindOnPage::class,
            NavigationAction.ScrollToText::class,
            NavigationAction.Click::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 4,
        constraints = BenchmarkConstraints(
            maxIterations = 11
        )
    )

    // ---- /faq/ accordion content ----

    /**
     * The FAQ page uses accordions for each question. The "Consultations" section
     * states that a first-time GP visit is at least 30 minutes.
     * Ideal: scroll_to_text("first") -> click accordion -> read -> answer.
     */
    private fun otpFaqFirstVisitDuration() = BenchmarkCase(
        id = "otp-faq-first-visit-duration",
        description = "OT&P FAQ: first-time GP consultation length (accordion)",
        pageSource = PageSource.Url("https://www.otandp.com/faq/"),
        query = "According to OT&P's FAQ, how long is a first-time GP consultation?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("30"), caseSensitive = false),
        idealActionSequence = listOf(
            NavigationAction.FindOnPage::class,
            NavigationAction.ScrollToText::class,
            NavigationAction.Click::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 4,
        constraints = BenchmarkConstraints(
            maxIterations = 11
        )
    )

    /**
     * The FAQ "Practitioners" section has an accordion about female doctors.
     * The answer lists Central, Repulse Bay, and Clearwater Bay clinics.
     * Ideal: scroll_to_text("female") -> click accordion -> read -> answer.
     */
    private fun otpFaqFemaleDoctors() = BenchmarkCase(
        id = "otp-faq-female-doctors",
        description = "OT&P FAQ: clinics with female doctors for Well Woman (accordion)",
        pageSource = PageSource.Url("https://www.otandp.com/faq/"),
        query = "At which OT&P clinics can you see a female doctor for a Well Woman check?",
        expectedOutcome = ExpectedOutcome.AnswerContains(
            listOf("central", "repulse bay", "clearwater bay"), caseSensitive = false
        ),
        idealActionSequence = listOf(
            NavigationAction.FindOnPage::class,
            NavigationAction.ScrollToText::class,
            NavigationAction.Click::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 4,
        constraints = BenchmarkConstraints(
            maxIterations = 11
        )
    )

    // ---- /contact and /about ----

    /**
     * The /contact page has a multi-pane SPA with a clinic list on the left
     * and a detail pane in the centre. The Central GP clinic address includes
     * "Century Square" and "D'Aguilar Street".
     * Ideal: find_on_page("Central General Practice") -> scroll_to_text -> read pane -> answer.
     */
    private fun otpCentralClinicAddress() = BenchmarkCase(
        id = "otp-central-clinic-address",
        description = "OT&P Central GP clinic address (multi-pane contact page)",
        pageSource = PageSource.Url("https://www.otandp.com/contact"),
        query = "What is the address of the OT&P Central General Practice Clinic?",
        expectedOutcome = ExpectedOutcome.AnswerContains(
            listOf("D'Aguilar", "Century Square"), caseSensitive = false
        ),
        idealActionSequence = listOf(
            NavigationAction.FindOnPage::class,
            NavigationAction.ScrollToText::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 10
        )
    )

    /**
     * The /about page (static text) states OT&P was founded in 1994 by
     * Dr Tim Trodd and Dr David Owens.
     * Ideal: scroll slightly or read directly -> answer.
     */
    private fun otpAboutFounded() = BenchmarkCase(
        id = "otp-about-founded",
        description = "OT&P founding year and founders (static about page)",
        pageSource = PageSource.Url("https://www.otandp.com/about"),
        query = "When was OT&P founded and who were the founders?",
        expectedOutcome = ExpectedOutcome.AnswerContains(
            listOf("1994", "Trodd", "Owens"), caseSensitive = false
        ),
        idealActionSequence = listOf(
            NavigationAction.FindOnPage::class,
            NavigationAction.ScrollToText::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 9
        )
    )
}
