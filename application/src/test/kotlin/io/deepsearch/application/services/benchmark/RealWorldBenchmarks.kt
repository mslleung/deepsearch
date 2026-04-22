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

    fun all(): List<BenchmarkCase> =
        sleekFlow() + otpHealthcare() + stripe() + notion() + cloudflare() + hubspot() + github()

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
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("chat"), caseSensitive = false),
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

    // ==================== Stripe Pricing (verified April 2026) ====================

    fun stripe(): List<BenchmarkCase> = listOf(
        stripeFaqSetupFees(),
        stripeFaqRefundStandard(),
        stripeMoreFeaturesExpand(),
        stripeFaqDiscounts(),
        stripeCustomRoi()
    )

    /**
     * FAQ accordion at the bottom of the pricing page. Must scroll past the
     * entire Standard/Custom pricing sections to reach FAQs, then expand the
     * accordion for "Do you have setup fees or monthly fees?".
     * Ideal: find_on_page("setup fees") -> scroll_to_text -> click accordion -> answer.
     */
    private fun stripeFaqSetupFees() = BenchmarkCase(
        id = "stripe-faq-setup-fees",
        description = "Stripe FAQ: setup/monthly/closure fees (accordion)",
        pageSource = PageSource.Url("https://stripe.com/pricing"),
        query = "According to Stripe's FAQ, does Stripe charge setup fees, monthly fees, or closure fees?",
        expectedOutcome = ExpectedOutcome.AnswerContains(
            listOf("does not charge setup fees", "monthly fees"), caseSensitive = false
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

    /**
     * FAQ accordion with two bullets: standard-pricing vs custom-pricing refund rules.
     * The agent must expand the accordion and read the correct bullet for standard
     * pricing (non-bank-transfer payments have no additional fees).
     * Ideal: find_on_page("refund") -> scroll_to_text -> click accordion -> answer.
     */
    private fun stripeFaqRefundStandard() = BenchmarkCase(
        id = "stripe-faq-refund-standard",
        description = "Stripe FAQ: refund fees for standard pricing (multi-bullet accordion)",
        pageSource = PageSource.Url("https://stripe.com/pricing"),
        query = "According to Stripe's FAQ, are there fees for refunds on standard pricing for non-bank-transfer payments?",
        expectedOutcome = ExpectedOutcome.AnswerContains(
            listOf("refund"), caseSensitive = false
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

    /**
     * Under the Payments section, a "More features" expand button reveals hidden
     * rows (3D Secure authentication, Authorization Boost, Adaptive Pricing,
     * Instant payouts, etc.) that are not listed until the section is expanded.
     * Ideal: find_on_page("3D Secure") -> scroll_to_text("More features") -> click expand -> answer.
     */
    private fun stripeMoreFeaturesExpand() = BenchmarkCase(
        id = "stripe-more-features-expand",
        description = "Stripe hidden Payments rows behind 'More features' expand",
        pageSource = PageSource.Url("https://stripe.com/pricing"),
        query = "Is 3D Secure authentication available on Stripe's standard pricing page under Payments?",
        expectedOutcome = ExpectedOutcome.AnswerContains(
            listOf("3D Secure"), caseSensitive = false
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

    /**
     * FAQ accordion: "Do you offer any discounts?" mentions custom pricing for
     * businesses with large processing volumes.
     * Ideal: find_on_page("discounts") -> scroll_to_text -> click accordion -> answer.
     */
    private fun stripeFaqDiscounts() = BenchmarkCase(
        id = "stripe-faq-discounts",
        description = "Stripe FAQ: volume discounts (accordion)",
        pageSource = PageSource.Url("https://stripe.com/pricing"),
        query = "According to Stripe's FAQ, does Stripe offer discounts for large processing volumes?",
        expectedOutcome = ExpectedOutcome.AnswerContains(
            listOf("custom pricing", "large"), caseSensitive = false
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

    /**
     * The Custom pricing section deep in the page cites a Forrester TEI study
     * claiming 326% ROI. Must scroll well past the Standard pricing document
     * and not confuse with other stats (100+, 350+) on the same page.
     * Ideal: find_on_page("326") -> scroll_to_text -> answer.
     */
    private fun stripeCustomRoi() = BenchmarkCase(
        id = "stripe-custom-roi",
        description = "Stripe Custom pricing section Forrester ROI stat (deep scroll)",
        pageSource = PageSource.Url("https://stripe.com/pricing"),
        query = "What ROI percentage does Stripe cite from Forrester on the custom pricing section?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("326")),
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

    // ==================== Notion Pricing (verified April 2026) ====================

    fun notion(): List<BenchmarkCase> = listOf(
        notionSamlSso(),
        notionPageHistory(),
        notionFaqPaymentFails(),
        notionCustomAgentsPrice(),
        notionAiDataRetention()
    )

    /**
     * SAML SSO availability is deep in the "Plans and features" comparison grid,
     * under the "Admin & security" section. Only Business and Enterprise have it.
     * Ideal: find_on_page("SAML") -> scroll_to_text -> answer.
     */
    private fun notionSamlSso() = BenchmarkCase(
        id = "notion-saml-sso",
        description = "Notion SAML SSO plan availability (deep comparison grid)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "Which Notion plans include SAML single sign-on (SSO)?",
        expectedOutcome = ExpectedOutcome.AnswerContains(
            listOf("business", "enterprise"), caseSensitive = false
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
     * Page history limits in the comparison grid: Free 7 days, Plus 30 days,
     * Business 90 days, Enterprise Unlimited. Must scroll to the correct row
     * and read the Plus column.
     * Ideal: find_on_page("Page history") -> scroll_to_text -> answer.
     */
    private fun notionPageHistory() = BenchmarkCase(
        id = "notion-page-history",
        description = "Notion Plus plan page history days (comparison grid)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "How many days of page history does the Notion Plus plan include?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("30")),
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
     * FAQ accordion at the bottom: "What happens if my payment fails?" states
     * payments may be retried up to 8 times within the next month.
     * Ideal: find_on_page("payment fails") -> scroll_to_text -> click accordion -> answer.
     */
    private fun notionFaqPaymentFails() = BenchmarkCase(
        id = "notion-faq-payment-fails",
        description = "Notion FAQ: payment retry count (accordion)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "According to Notion's FAQ, how many times may a failed payment be retried?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("8")),
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
     * Custom Agents pricing is in a small callout near the plan cards, not in
     * the main tier headlines: "$10 per 1,000 credits" after the free trial.
     * Ideal: find_on_page("Custom Agents") -> scroll_to_text -> answer.
     */
    private fun notionCustomAgentsPrice() = BenchmarkCase(
        id = "notion-custom-agents-price",
        description = "Notion Custom Agents usage price (small callout, not tier headline)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "What is the price for Notion Custom Agents after the free trial?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("10", "1,000 credits")),
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
     * In the "Notion AI" section of the comparison grid, the "Data retention" row
     * shows different values: Free/Plus/Business have "30 day retention" while
     * Enterprise has "Zero data retention". Must read correct columns.
     * Ideal: find_on_page("Data retention") -> scroll_to_text -> answer.
     */
    private fun notionAiDataRetention() = BenchmarkCase(
        id = "notion-ai-data-retention",
        description = "Notion AI data retention: Enterprise vs Business (comparison grid)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "What is the Notion AI data retention policy for Enterprise vs Business?",
        expectedOutcome = ExpectedOutcome.AnswerContains(
            listOf("zero data retention", "30 day"), caseSensitive = false
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

    // ==================== Cloudflare Plans (verified April 2026) ====================

    fun cloudflare(): List<BenchmarkCase> = listOf(
        cfCompareUploadSize(),
        cfDevPlatformKvReads(),
        cfWorkersOverage(),
        cfFaqBillingDate(),
        cfProMonthlyPrice()
    )

    /**
     * "Compare all features" opens a large scrollable overlay (not a new page).
     * The "Client Max Upload Size (MB)" row shows Free 100, Pro 100, Business 200,
     * Contract 500+. Must open the overlay, scroll inside it, and read the
     * Business column.
     * Ideal: find_on_page -> scroll_to_text("Compare all features") -> click -> scroll_to_text("Upload") -> answer.
     */
    private fun cfCompareUploadSize() = BenchmarkCase(
        id = "cf-compare-upload-size",
        description = "Cloudflare Compare overlay: Client Max Upload Size for Business",
        pageSource = PageSource.Url("https://www.cloudflare.com/plans/"),
        query = "In Cloudflare's full feature comparison, what is the Client Max Upload Size (MB) for the Business plan?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("200")),
        idealActionSequence = listOf(
            NavigationAction.FindOnPage::class,
            NavigationAction.ScrollToText::class,
            NavigationAction.Click::class,
            NavigationAction.ScrollToText::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 5,
        constraints = BenchmarkConstraints(
            maxIterations = 14
        )
    )

    /**
     * Clicking "Developer Platform" tab changes the URL to /plans/developer-platform/.
     * Then "Workers KV" sub-tab must be selected to see KV limits. Free tier
     * includes 100K key-value reads per day.
     * Ideal: click "Developer Platform" -> click "Workers KV" -> scroll_to_text -> answer.
     */
    private fun cfDevPlatformKvReads() = BenchmarkCase(
        id = "cf-dev-platform-kv-reads",
        description = "Cloudflare Developer Platform: Workers KV free-tier reads (route change + nested sub-tab)",
        pageSource = PageSource.Url("https://www.cloudflare.com/plans/"),
        query = "On Cloudflare's Developer Platform, how many Workers KV key-value reads per day are included on the free tier?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("100")),
        idealActionSequence = listOf(
            NavigationAction.Click::class,
            NavigationAction.Click::class,
            NavigationAction.ScrollToText::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 4,
        constraints = BenchmarkConstraints(
            maxIterations = 12
        )
    )

    /**
     * On Developer Platform -> Cloudflare Workers sub-tab, the paid tier shows
     * "10 million requests/month included + $0.30 per additional million".
     * Ideal: click "Developer Platform" -> click "Workers" -> scroll_to_text -> answer.
     */
    private fun cfWorkersOverage() = BenchmarkCase(
        id = "cf-workers-overage",
        description = "Cloudflare Workers Paid overage per million (Developer Platform sub-tab)",
        pageSource = PageSource.Url("https://www.cloudflare.com/plans/"),
        query = "What is the overage charge per million requests for Cloudflare Workers Paid?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("0.30")),
        idealActionSequence = listOf(
            NavigationAction.Click::class,
            NavigationAction.Click::class,
            NavigationAction.ScrollToText::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 4,
        constraints = BenchmarkConstraints(
            maxIterations = 12
        )
    )

    /**
     * FAQ accordion: "When is my billing date?" contains an example: "If you
     * sign up on January 10, all future charges will be billed on the 10th of
     * every month." Requires inference from the example.
     * Ideal: find_on_page("billing date") -> scroll_to_text -> click accordion -> answer.
     */
    private fun cfFaqBillingDate() = BenchmarkCase(
        id = "cf-faq-billing-date",
        description = "Cloudflare FAQ: billing date from example (accordion, inferential)",
        pageSource = PageSource.Url("https://www.cloudflare.com/plans/"),
        query = "According to Cloudflare's FAQ, if you first sign up on January 10, on which day of the month are future charges billed?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("10th")),
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
     * The Pro plan headline shows $20/month (annual rate). The subordinate line
     * says "$25/mo billed monthly". Agent must read the secondary text, not the
     * headline, to answer the monthly-billing price.
     * Ideal: find_on_page("Pro") -> scroll_to_text -> answer.
     */
    private fun cfProMonthlyPrice() = BenchmarkCase(
        id = "cf-pro-monthly-price",
        description = "Cloudflare Pro monthly (not annual) price (secondary billing text)",
        pageSource = PageSource.Url("https://www.cloudflare.com/plans/"),
        query = "What is the Cloudflare Pro plan price per month if billed monthly (not the annual rate)?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("25")),
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

    // ==================== HubSpot Pricing (verified April 2026) ====================

    fun hubspot(): List<BenchmarkCase> = listOf(
        hubspotFaqEmailLimit(),
        hubspotFaqContactsEnterprise(),
        hubspotAeoTrialPrompts(),
        hubspotAeoIncludedTiers(),
        hubspotOnboardingFee()
    )

    /**
     * FAQ accordion on Marketing Hub pricing: "How many emails can I send?" has
     * three bullets (Starter 5x, Professional 10x, Enterprise 20x the contact
     * tier). Agent must pick the Professional bullet.
     * Ideal: find_on_page("emails") -> scroll_to_text -> click accordion -> answer.
     */
    private fun hubspotFaqEmailLimit() = BenchmarkCase(
        id = "hubspot-faq-email-limit",
        description = "HubSpot FAQ: Professional email send limit multiplier (multi-bullet accordion)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "For HubSpot Marketing Hub Professional, what is the monthly email send limit relative to the marketing contact tier?",
        expectedOutcome = ExpectedOutcome.AnswerContains(
            listOf("10 times"), caseSensitive = false
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

    /**
     * FAQ accordion: "How many marketing contacts?" lists included contacts per
     * tier. Enterprise includes 10,000 marketing contacts.
     * Ideal: find_on_page("marketing contacts") -> scroll_to_text -> click accordion -> answer.
     */
    private fun hubspotFaqContactsEnterprise() = BenchmarkCase(
        id = "hubspot-faq-contacts-enterprise",
        description = "HubSpot FAQ: Enterprise included marketing contacts (accordion)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "How many marketing contacts are included with HubSpot Marketing Hub Enterprise?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("10,000")),
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
     * The HubSpot AEO spotlight card has fine print: "Trial includes 10 prompts
     * tracked in ChatGPT." Agent must not confuse with the subscription stat
     * "Track 25 prompts across 3 answer engines daily."
     * Ideal: find_on_page("AEO") -> scroll_to_text -> answer.
     */
    private fun hubspotAeoTrialPrompts() = BenchmarkCase(
        id = "hubspot-aeo-trial-prompts",
        description = "HubSpot AEO trial ChatGPT prompts (hero card fine print)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "During the HubSpot AEO trial, how many prompts are tracked in ChatGPT?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("10")),
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
     * The AEO card has a footnote: "*Also included in Marketing Hub Professional
     * and Enterprise". Not in the main tier bullet list.
     * Ideal: find_on_page("AEO") -> scroll_to_text -> answer.
     */
    private fun hubspotAeoIncludedTiers() = BenchmarkCase(
        id = "hubspot-aeo-included-tiers",
        description = "HubSpot AEO included tiers (footnote, not tier bullets)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "Beyond standalone purchase, which HubSpot Marketing Hub editions include AEO?",
        expectedOutcome = ExpectedOutcome.AnswerContains(
            listOf("professional", "enterprise"), caseSensitive = false
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

    /**
     * Below the Professional tier CTA there is a small footnote with asterisk:
     * "Cost shown does not include the required, one-time Professional Onboarding
     * for a fee of [amount]." Easy to miss vs the headline price.
     * Ideal: find_on_page("Onboarding") -> scroll_to_text -> answer.
     */
    private fun hubspotOnboardingFee() = BenchmarkCase(
        id = "hubspot-onboarding-fee",
        description = "HubSpot Professional onboarding fee (asterisk footnote)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "What is the required one-time Professional Onboarding fee for HubSpot Marketing Hub Professional?",
        expectedOutcome = ExpectedOutcome.AnswerContains(
            listOf("onboarding"), caseSensitive = false
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

    // ==================== GitHub Pricing (verified April 2026) ====================

    fun github(): List<BenchmarkCase> = listOf(
        ghActionsMinutesTeam(),
        ghDataResidencyCloud(),
        ghCodespacesFreeHours(),
        ghCopilotFreeLimits(),
        ghLfsPrice()
    )

    /**
     * "Compare all features" anchor-jumps to #compare-features, revealing a large
     * comparison table. The GitHub Actions row shows 2,000 / 3,000 / 50,000
     * minutes per month for Free / Team / Enterprise.
     * Ideal: find_on_page("Actions") -> scroll_to_text("Compare all features") -> click -> scroll_to_text("Actions") -> answer.
     */
    private fun ghActionsMinutesTeam() = BenchmarkCase(
        id = "gh-actions-minutes-team",
        description = "GitHub Actions minutes for Team (anchor jump + deep comparison table)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "How many GitHub Actions minutes per month does the GitHub Team plan include?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("3,000")),
        idealActionSequence = listOf(
            NavigationAction.FindOnPage::class,
            NavigationAction.ScrollToText::class,
            NavigationAction.Click::class,
            NavigationAction.ScrollToText::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 5,
        constraints = BenchmarkConstraints(
            maxIterations = 13
        )
    )

    /**
     * The Enterprise plan card has an expandable "Data residency" row. When
     * expanded, the description mentions Microsoft Azure as the cloud provider.
     * Answer is hidden inside the collapsed disclosure.
     * Ideal: find_on_page("Data residency") -> scroll_to_text -> click expand -> answer.
     */
    private fun ghDataResidencyCloud() = BenchmarkCase(
        id = "gh-data-residency-cloud",
        description = "GitHub Enterprise data residency cloud provider (expandable plan-card row)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "Which cloud provider does GitHub Enterprise Cloud use for data residency?",
        expectedOutcome = ExpectedOutcome.AnswerContains(
            listOf("Microsoft Azure"), caseSensitive = false
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

    /**
     * In the compare-features table, the GitHub Codespaces row can be expanded
     * to reveal a description mentioning "On a 2-core machine, you would get
     * 60 hours free." The number is inside long explanatory copy.
     * Ideal: find_on_page("Codespaces") -> scroll_to_text -> click expand -> answer.
     */
    private fun ghCodespacesFreeHours() = BenchmarkCase(
        id = "gh-codespaces-free-hours",
        description = "GitHub Codespaces free hours for 2-core (expandable compare-table row)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "How many free hours does GitHub Codespaces give on a 2-core machine?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("60")),
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
     * The Copilot add-on section (not in the main plan grid) states "up to
     * 2,000 completions and 50 chat requests per month" for the free tier.
     * Ideal: find_on_page("Copilot") -> scroll_to_text -> answer.
     */
    private fun ghCopilotFreeLimits() = BenchmarkCase(
        id = "gh-copilot-free-limits",
        description = "GitHub Copilot free tier limits (add-on section, not main grid)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "How many free completions and chat requests per month does GitHub Copilot offer?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("2,000", "50")),
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
     * Git Large File Storage pricing is in an add-on block separate from the
     * plan comparison: "$5 per month for 50 GB bandwidth and 50 GB of storage."
     * Ideal: find_on_page("Large File Storage") -> scroll_to_text -> answer.
     */
    private fun ghLfsPrice() = BenchmarkCase(
        id = "gh-lfs-price",
        description = "GitHub Git LFS monthly price (add-on section)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "How much does Git Large File Storage cost per month on GitHub?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("5", "50 GB")),
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
}
