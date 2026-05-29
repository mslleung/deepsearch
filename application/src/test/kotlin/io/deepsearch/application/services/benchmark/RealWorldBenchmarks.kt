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
        sleekFlow() + otpHealthcare() + stripe() + notion() + cloudflare() + hubspot() + github() + intercom()

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
        otpAboutFounded(),
        otpNonexistentPediatric(),
        otpFaqTelemedicine(),
        otpAboutIpo(),
        otpSpecialisedDermatology(),
        otpBodycheckMri()
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
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 9
        )
    )

    // ---- Negative cases: information NOT on the page ----

    /**
     * The /body-check/ page lists Standard, Comprehensive, Ultra, and specialised
     * packages — but no "Pediatric Health Check". The agent should explore the
     * comparison table, find no match, and give up.
     */
    private fun otpNonexistentPediatric() = BenchmarkCase(
        id = "otp-nonexistent-pediatric",
        description = "OT&P Pediatric Health Check (does not exist)",
        pageSource = PageSource.Url("https://www.otandp.com/body-check/"),
        query = "What is the price of the OT&P Pediatric Health Check package?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        idealActionSequence = listOf(
            NavigationAction.GiveUp::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
    )

    /**
     * The /faq/ page covers Location & Booking, Consultations & Prescriptions,
     * and Practitioners — but has no question about telemedicine or virtual
     * consultation services.
     */
    private fun otpFaqTelemedicine() = BenchmarkCase(
        id = "otp-faq-telemedicine",
        description = "OT&P FAQ: telemedicine/virtual consultations (not on page)",
        pageSource = PageSource.Url("https://www.otandp.com/faq/"),
        query = "Does OT&P offer telemedicine or virtual consultation services?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        idealActionSequence = listOf(
            NavigationAction.GiveUp::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
    )

    /**
     * The /about page describes OT&P's history, founders, and mission — but
     * OT&P is a private clinic group and has no IPO information.
     */
    private fun otpAboutIpo() = BenchmarkCase(
        id = "otp-about-ipo",
        description = "OT&P IPO date (does not exist — private company)",
        pageSource = PageSource.Url("https://www.otandp.com/about"),
        query = "When did OT&P Healthcare go public and what was the IPO price?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        idealActionSequence = listOf(
            NavigationAction.GiveUp::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 6
        )
    )

    /**
     * The /body-check/specialised-health-checks page has Cardiovascular Risk,
     * Well Woman, Fit at Fifty, and Cancer Risk packages — but no
     * "Dermatology Screening Package".
     */
    private fun otpSpecialisedDermatology() = BenchmarkCase(
        id = "otp-specialised-dermatology",
        description = "OT&P Dermatology Screening (does not exist)",
        pageSource = PageSource.Url("https://www.otandp.com/body-check/specialised-health-checks"),
        query = "What is the price of the OT&P Dermatology Screening Package?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        idealActionSequence = listOf(
            NavigationAction.GiveUp::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
    )

    /**
     * The /body-check/ comparison table covers blood tests, ECG, ultrasound,
     * spirometry, etc. — but no MRI scan in any package.
     */
    private fun otpBodycheckMri() = BenchmarkCase(
        id = "otp-bodycheck-mri",
        description = "OT&P MRI scan inclusion (does not exist)",
        pageSource = PageSource.Url("https://www.otandp.com/body-check/"),
        query = "Which OT&P body check packages include an MRI scan?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        idealActionSequence = listOf(
            NavigationAction.GiveUp::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
    )

    // ==================== Stripe Pricing (verified April 2026) ====================

    fun stripe(): List<BenchmarkCase> = listOf(
        stripeDomesticCardRate(),
        stripeInternationalSurcharge(),
        stripeAchDebitRate(),
        stripeTerminalDomesticRate(),
        stripeBillingUsageRate(),
        stripeTaxApiPrice(),
        stripeAtlasFee(),
        stripeRadarFraudTeamsCustom(),
        stripeIssuingVirtualCard(),
        stripeFaqSetupFees(),
        stripeFaqRefundStandard(),
        stripeMoreFeaturesExpand(),
        stripeFaqDiscounts(),
        stripeCustomRoi(),
        stripeFreePlan(),
        stripePhoneSupport(),
        stripeEscrowService(),
        stripeChargebackInsurance()
    )

    // ---- Easy: visible after scrolling ----

    /**
     * The headline pricing is at the very top of the page: "2.9% + 30¢ per
     * successful transaction for domestic cards".
     * Ideal: read directly -> answer.
     */
    private fun stripeDomesticCardRate() = BenchmarkCase(
        id = "stripe-domestic-card-rate",
        description = "Stripe standard domestic card processing rate (headline pricing)",
        pageSource = PageSource.Url("https://stripe.com/pricing"),
        query = "What is Stripe's standard processing fee for domestic card transactions?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("2.9%", "30")),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 2,
        constraints = BenchmarkConstraints(
            maxIterations = 7
        )
    )

    /**
     * International card surcharge is listed just below the headline rate:
     * "+ 1.5% for international cards".
     * Ideal: scroll slightly -> answer.
     */
    private fun stripeInternationalSurcharge() = BenchmarkCase(
        id = "stripe-international-surcharge",
        description = "Stripe international card surcharge (near top)",
        pageSource = PageSource.Url("https://stripe.com/pricing"),
        query = "What additional fee does Stripe charge for international card transactions?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("1.5%")),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 2,
        constraints = BenchmarkConstraints(
            maxIterations = 7
        )
    )

    /**
     * ACH Direct Debit is in the "Bank debits, bank transfers" section:
     * "0.8% ACH Direct Debit $5.00 cap".
     * Ideal: scroll to bank debits section -> answer.
     */
    private fun stripeAchDebitRate() = BenchmarkCase(
        id = "stripe-ach-debit-rate",
        description = "Stripe ACH Direct Debit rate and cap (Payments section)",
        pageSource = PageSource.Url("https://stripe.com/pricing"),
        query = "What is Stripe's fee for ACH Direct Debit transactions and what is the maximum fee?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("0.8%", "5.00")),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
    )

    /**
     * Terminal (in-person) pricing is deeper on the page: "2.7% + 5¢ per
     * successful transaction for domestic cards".
     * Ideal: scroll to Terminal section -> answer.
     */
    private fun stripeTerminalDomesticRate() = BenchmarkCase(
        id = "stripe-terminal-domestic-rate",
        description = "Stripe Terminal in-person domestic card rate (mid-page)",
        pageSource = PageSource.Url("https://stripe.com/pricing"),
        query = "What is Stripe Terminal's processing fee for in-person domestic card transactions?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("2.7%", "5")),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 9
        )
    )

    // ---- Medium: requires expanding a section ----

    /**
     * Billing usage-based rate is under the Billing section which may require
     * expanding to see the pay-as-you-go option: "0.7% of Billing volume".
     * Ideal: scroll to Billing -> click expand -> answer.
     */
    private fun stripeBillingUsageRate() = BenchmarkCase(
        id = "stripe-billing-usage-rate",
        description = "Stripe Billing usage-based rate (expandable section)",
        pageSource = PageSource.Url("https://stripe.com/pricing"),
        query = "What is Stripe's usage-based Billing fee as a percentage of volume?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("0.7%")),
        idealActionSequence = listOf(
            NavigationAction.Click::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 4,
        constraints = BenchmarkConstraints(
            maxIterations = 10
        )
    )

    /**
     * Tax API integration pricing is in the Tax section: "$0.50 per transaction,
     * where you're registered to collect taxes". Distinct from the no-code 0.5%
     * rate; agent must identify the API-specific line.
     * Ideal: scroll to Tax -> click expand -> answer.
     */
    private fun stripeTaxApiPrice() = BenchmarkCase(
        id = "stripe-tax-api-price",
        description = "Stripe Tax API per-transaction price (expandable Tax section)",
        pageSource = PageSource.Url("https://stripe.com/pricing"),
        query = "What does Stripe Tax charge per transaction for API integrations?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("0.50")),
        idealActionSequence = listOf(
            NavigationAction.Click::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 4,
        constraints = BenchmarkConstraints(
            maxIterations = 11
        )
    )

    /**
     * Atlas incorporation fee is in the "More" section at the bottom of the
     * main pricing list: "$500 one-time setup fee". Requires significant
     * scrolling past all payment products.
     * Ideal: scroll deep to Atlas section -> answer.
     */
    private fun stripeAtlasFee() = BenchmarkCase(
        id = "stripe-atlas-fee",
        description = "Stripe Atlas incorporation one-time fee (deep scroll)",
        pageSource = PageSource.Url("https://stripe.com/pricing"),
        query = "What is the one-time setup fee for Stripe Atlas incorporation?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("500")),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 10
        )
    )

    // ---- Hard: deep / multiple interactions ----

    /**
     * Radar for Fraud Teams with custom pricing charges "$0.07 per screened
     * transaction". This is hidden behind the "More features" expand within
     * the Radar section and requires distinguishing from the standard pricing
     * ($0.02) and the included-with-payments tier.
     * Ideal: scroll to Radar -> click expand -> read custom pricing line -> answer.
     */
    private fun stripeRadarFraudTeamsCustom() = BenchmarkCase(
        id = "stripe-radar-fraud-custom",
        description = "Stripe Radar for Fraud Teams custom pricing per transaction (More features)",
        pageSource = PageSource.Url("https://stripe.com/pricing"),
        query = "What does Stripe Radar for Fraud Teams charge per screened transaction for accounts with custom pricing?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("0.07")),
        idealActionSequence = listOf(
            NavigationAction.Click::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 4,
        constraints = BenchmarkConstraints(
            maxIterations = 12
        )
    )

    /**
     * Issuing virtual card price ($0.10 per virtual card) is deep in the
     * Money Management section under Issuing. Physical card is $3.50. Agent
     * must scroll past Connect, Treasury, and Global Payouts to reach Issuing.
     * Ideal: scroll deep to Issuing -> answer.
     */
    private fun stripeIssuingVirtualCard() = BenchmarkCase(
        id = "stripe-issuing-virtual-card",
        description = "Stripe Issuing virtual card price (deep Money Management section)",
        pageSource = PageSource.Url("https://stripe.com/pricing"),
        query = "How much does Stripe charge per virtual card issued?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("0.10")),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 10
        )
    )

    // ---- Negative: information NOT on the page ----

    /**
     * Stripe has no free tier or free plan — it is purely pay-as-you-go.
     * The page explicitly states "No setup fees, monthly fees, or hidden fees"
     * but never mentions a free plan with included transactions.
     */
    private fun stripeFreePlan() = BenchmarkCase(
        id = "stripe-free-plan",
        description = "Stripe free plan/tier (does not exist)",
        pageSource = PageSource.Url("https://stripe.com/pricing"),
        query = "What is included in Stripe's free plan and how many free transactions per month does it offer?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        idealActionSequence = listOf(
            NavigationAction.GiveUp::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
    )

    /**
     * The pricing page lists email and web-based support but does not mention
     * phone support for standard-pricing users. Phone support is only available
     * via the Custom package.
     */
    private fun stripePhoneSupport() = BenchmarkCase(
        id = "stripe-phone-support",
        description = "Stripe phone support for standard users (not available)",
        pageSource = PageSource.Url("https://stripe.com/pricing"),
        query = "What is the phone number for Stripe standard-pricing customer support?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        idealActionSequence = listOf(
            NavigationAction.GiveUp::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
    )

    /**
     * Stripe does not offer escrow or held-funds services on its pricing page.
     * No mention of escrow anywhere.
     */
    private fun stripeEscrowService() = BenchmarkCase(
        id = "stripe-escrow-service",
        description = "Stripe escrow/held-funds service (does not exist)",
        pageSource = PageSource.Url("https://stripe.com/pricing"),
        query = "What is the fee for Stripe's escrow service that holds funds between buyer and seller?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        idealActionSequence = listOf(
            NavigationAction.GiveUp::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
    )

    /**
     * Stripe does not offer chargeback insurance or guarantee on its pricing
     * page. Disputes are handled at $15/dispute or via Smart Disputes, but
     * there is no insurance product.
     */
    private fun stripeChargebackInsurance() = BenchmarkCase(
        id = "stripe-chargeback-insurance",
        description = "Stripe chargeback insurance/guarantee (does not exist)",
        pageSource = PageSource.Url("https://stripe.com/pricing"),
        query = "How much does Stripe's chargeback insurance cost to guarantee against all dispute losses?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        idealActionSequence = listOf(
            NavigationAction.GiveUp::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
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
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 10
        )
    )

    // ==================== Notion Pricing (verified April 2026) ====================

    fun notion(): List<BenchmarkCase> = listOf(
        notionPlusPrice(),
        notionBusinessPrice(),
        notionFreeFileUpload(),
        notionGuestLimitFree(),
        notionAutomationsPlans(),
        notionFaqStudentDiscount(),
        notionFaqRefundPolicy(),
        notionCustomDomainPrice(),
        notionChartsFreeLimit(),
        notionSamlSso(),
        notionPageHistory(),
        notionFaqPaymentFails(),
        notionCustomAgentsPrice(),
        notionAiDataRetention(),
        notionPhoneSupport(),
        notionVideoConferencing(),
        notionLifetimePlan()
    )

    // ---- Easy: visible plan cards near top ----

    /**
     * Plus plan price ($10/member/month) is prominently displayed in the
     * plan cards near the top of the pricing page.
     * Ideal: scroll slightly to plan cards -> answer.
     */
    private fun notionPlusPrice() = BenchmarkCase(
        id = "notion-plus-price",
        description = "Notion Plus plan price per member (plan card near top)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "What is the price of the Notion Plus plan per member per month?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("10")),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 2,
        constraints = BenchmarkConstraints(
            maxIterations = 7
        )
    )

    /**
     * Business plan price ($20/member/month) is in the recommended plan card.
     * Ideal: scroll to plan cards -> answer.
     */
    private fun notionBusinessPrice() = BenchmarkCase(
        id = "notion-business-price",
        description = "Notion Business plan price per member (recommended card)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "What is the price of the Notion Business plan per member per month?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("20")),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 2,
        constraints = BenchmarkConstraints(
            maxIterations = 7
        )
    )

    /**
     * The Free plan has a 5 MB file upload limit, shown in the plan card
     * features and the comparison grid "File uploads" row.
     * Ideal: scroll to comparison grid -> answer.
     */
    private fun notionFreeFileUpload() = BenchmarkCase(
        id = "notion-free-file-upload",
        description = "Notion Free plan file upload size limit (comparison grid)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "What is the maximum file upload size on Notion's Free plan?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("5 MB")),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 9
        )
    )

    // ---- Medium: comparison grid rows requiring scroll ----

    /**
     * The "External guest limit" row in the comparison grid shows Free has 10
     * guests while paid plans have Unlimited. Must scroll to the Sharing &
     * collaboration section.
     * Ideal: scroll to Sharing section -> answer.
     */
    private fun notionGuestLimitFree() = BenchmarkCase(
        id = "notion-guest-limit-free",
        description = "Notion Free plan external guest limit (comparison grid)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "How many external guests can you invite on Notion's Free plan?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("10")),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 9
        )
    )

    /**
     * The "Automations" row in the comparison grid shows Free has "Basic
     * buttons only" while Plus has "Custom database automations". Deep in
     * the "API automations & integrations" section.
     * Ideal: scroll deep to automations row -> answer.
     */
    private fun notionAutomationsPlans() = BenchmarkCase(
        id = "notion-automations-plans",
        description = "Notion automations: Free vs Plus capabilities (deep comparison grid)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "What automation capabilities does the Notion Plus plan offer compared to the Free plan?",
        expectedOutcome = ExpectedOutcome.AnswerContains(
            listOf("database automations"), caseSensitive = false
        ),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 10
        )
    )

    /**
     * FAQ accordion: "Do you offer student discounts?" states the Plus Plan
     * (with 1-member limit) is free for students and educators.
     * Ideal: scroll to FAQ -> click accordion -> answer.
     */
    private fun notionFaqStudentDiscount() = BenchmarkCase(
        id = "notion-faq-student-discount",
        description = "Notion FAQ: student discount details (accordion)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "According to Notion's FAQ, what discount do students get?",
        expectedOutcome = ExpectedOutcome.AnswerContains(
            listOf("free", "student"), caseSensitive = false
        ),
        idealActionSequence = listOf(
            NavigationAction.Click::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 4,
        constraints = BenchmarkConstraints(
            maxIterations = 11
        )
    )

    /**
     * FAQ accordion: "How do refunds work?" states full refund within 3 days
     * for monthly billing or 30 days for annual billing.
     * Ideal: scroll to FAQ -> click accordion -> answer.
     */
    private fun notionFaqRefundPolicy() = BenchmarkCase(
        id = "notion-faq-refund-policy",
        description = "Notion FAQ: refund policy timeframes (accordion)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "According to Notion's FAQ, within how many days of signing up can you get a refund for monthly vs annual billing?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("three days", "30 days")),
        idealActionSequence = listOf(
            NavigationAction.Click::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 4,
        constraints = BenchmarkConstraints(
            maxIterations = 12
        )
    )

    // ---- Hard: deep grid rows ----

    /**
     * Custom domain pricing is buried deep in the "Web publishing" section
     * of the comparison grid: "$8/month/domain paid annually, or $10/month
     * per domain paid monthly". Requires scrolling past Content, Sharing,
     * Notion AI, Connected apps, Database features, and Developer platform.
     * Ideal: scroll very deep -> answer.
     */
    private fun notionCustomDomainPrice() = BenchmarkCase(
        id = "notion-custom-domain-price",
        description = "Notion custom domain price (deep in Web publishing section)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "How much does a custom domain cost on Notion Sites when paid annually?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("8")),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 11
        )
    )

    /**
     * The "Charts" row in the Database features section shows Free plan gets
     * 1 chart while paid plans get Unlimited. Very deep in the grid.
     * Ideal: scroll very deep to Database features -> answer.
     */
    private fun notionChartsFreeLimit() = BenchmarkCase(
        id = "notion-charts-free-limit",
        description = "Notion Free plan chart limit (deep Database features section)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "How many charts can you create on Notion's Free plan?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("1")),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 11
        )
    )

    // ---- Negative: information NOT on the page ----

    /**
     * Notion does not offer phone support. The Support section mentions
     * "Priority support" and "Premium support" but no phone number or
     * phone-based support option.
     */
    private fun notionPhoneSupport() = BenchmarkCase(
        id = "notion-phone-support",
        description = "Notion phone support (does not exist)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "What is Notion's phone support number and what hours is it available?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        idealActionSequence = listOf(
            NavigationAction.GiveUp::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
    )

    /**
     * Notion is a productivity/wiki tool, not a video conferencing platform.
     * No video calling or meeting features are listed on the pricing page.
     */
    private fun notionVideoConferencing() = BenchmarkCase(
        id = "notion-video-conferencing",
        description = "Notion video conferencing feature (does not exist)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "Which Notion plans include built-in video conferencing with screen sharing?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        idealActionSequence = listOf(
            NavigationAction.GiveUp::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
    )

    /**
     * Notion only offers monthly and annual subscriptions. There is no
     * lifetime or perpetual license option mentioned anywhere on the page.
     */
    private fun notionLifetimePlan() = BenchmarkCase(
        id = "notion-lifetime-plan",
        description = "Notion lifetime/perpetual plan (does not exist)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "What is the price of Notion's lifetime plan with a one-time payment?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        idealActionSequence = listOf(
            NavigationAction.GiveUp::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
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
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 10
        )
    )

    // ==================== Cloudflare Plans (verified April 2026) ====================

    fun cloudflare(): List<BenchmarkCase> = listOf(
        cfFreePrice(),
        cfBusinessPrice(),
        cfEnterpriseSales(),
        cfFaqPaymentMethods(),
        cfFaqPlanDowngrade(),
        cfCompareWafRules(),
        cfDevPlatformR2Storage(),
        cfDevPlatformPagesBuilds(),
        cfCompareUploadSize(),
        cfDevPlatformKvReads(),
        cfWorkersOverage(),
        cfFaqBillingDate(),
        cfProMonthlyPrice(),
        cfVideoStreaming(),
        cfPhoneSupportPro(),
        cfEmailHosting(),
        cfDedicatedIp()
    )

    // ---- Easy: visible plan cards ----

    /**
     * The Free plan is prominently shown at the top with "$0/month" pricing.
     * Ideal: read directly from plan cards -> answer.
     */
    private fun cfFreePrice() = BenchmarkCase(
        id = "cf-free-price",
        description = "Cloudflare Free plan price (plan card near top)",
        pageSource = PageSource.Url("https://www.cloudflare.com/plans/"),
        query = "What does the Cloudflare Free plan cost per month?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("0", "free"), caseSensitive = false),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 2,
        constraints = BenchmarkConstraints(
            maxIterations = 7
        )
    )

    /**
     * Business plan shows $200/month in the plan card.
     * Ideal: scroll to Business card -> answer.
     */
    private fun cfBusinessPrice() = BenchmarkCase(
        id = "cf-business-price",
        description = "Cloudflare Business plan monthly price (plan card)",
        pageSource = PageSource.Url("https://www.cloudflare.com/plans/"),
        query = "What is the Cloudflare Business plan price per month?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("200")),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 2,
        constraints = BenchmarkConstraints(
            maxIterations = 7
        )
    )

    /**
     * Enterprise plan requires contacting sales — no public price listed.
     * Ideal: scroll to Enterprise card -> answer.
     */
    private fun cfEnterpriseSales() = BenchmarkCase(
        id = "cf-enterprise-sales",
        description = "Cloudflare Enterprise pricing approach (contact sales)",
        pageSource = PageSource.Url("https://www.cloudflare.com/plans/"),
        query = "How do you get pricing for Cloudflare's Enterprise plan?",
        expectedOutcome = ExpectedOutcome.AnswerContains(
            listOf("contact", "sales"), caseSensitive = false
        ),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 2,
        constraints = BenchmarkConstraints(
            maxIterations = 7
        )
    )

    // ---- Medium: FAQ accordions ----

    /**
     * FAQ accordion: "What forms of payment do you accept?" lists credit
     * cards and PayPal as accepted payment methods.
     * Ideal: scroll to FAQ -> click accordion -> answer.
     */
    private fun cfFaqPaymentMethods() = BenchmarkCase(
        id = "cf-faq-payment-methods",
        description = "Cloudflare FAQ: accepted payment methods (accordion)",
        pageSource = PageSource.Url("https://www.cloudflare.com/plans/"),
        query = "According to Cloudflare's FAQ, what payment methods are accepted?",
        expectedOutcome = ExpectedOutcome.AnswerContains(
            listOf("credit", "PayPal"), caseSensitive = false
        ),
        idealActionSequence = listOf(
            NavigationAction.Click::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 4,
        constraints = BenchmarkConstraints(
            maxIterations = 11
        )
    )

    /**
     * FAQ accordion: "Can I downgrade my plan?" states you can downgrade
     * at any time and the change takes effect at the end of the billing period.
     * Ideal: scroll to FAQ -> click accordion -> answer.
     */
    private fun cfFaqPlanDowngrade() = BenchmarkCase(
        id = "cf-faq-plan-downgrade",
        description = "Cloudflare FAQ: plan downgrade policy (accordion)",
        pageSource = PageSource.Url("https://www.cloudflare.com/plans/"),
        query = "According to Cloudflare's FAQ, can you downgrade your plan and when does it take effect?",
        expectedOutcome = ExpectedOutcome.AnswerContains(
            listOf("downgrade"), caseSensitive = false
        ),
        idealActionSequence = listOf(
            NavigationAction.Click::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 4,
        constraints = BenchmarkConstraints(
            maxIterations = 11
        )
    )

    /**
     * WAF custom rules differ by plan in the "Compare all features" overlay.
     * Free has limited, Pro has custom rules, Business has more.
     * Ideal: click "Compare all features" -> scroll to WAF row -> answer.
     */
    private fun cfCompareWafRules() = BenchmarkCase(
        id = "cf-compare-waf-rules",
        description = "Cloudflare Compare overlay: WAF custom rules by plan",
        pageSource = PageSource.Url("https://www.cloudflare.com/plans/"),
        query = "In Cloudflare's full feature comparison, how many custom WAF rules does the Pro plan include?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("5")),
        idealActionSequence = listOf(
            NavigationAction.Click::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 4,
        constraints = BenchmarkConstraints(
            maxIterations = 14
        )
    )

    // ---- Hard: Developer Platform sub-tabs ----

    /**
     * Developer Platform -> R2 sub-tab shows storage pricing. Free tier
     * includes 10 GB storage per month. Requires clicking Developer Platform
     * tab then R2 sub-tab.
     * Ideal: click "Developer Platform" -> click "R2" -> answer.
     */
    private fun cfDevPlatformR2Storage() = BenchmarkCase(
        id = "cf-dev-platform-r2-storage",
        description = "Cloudflare R2 free-tier storage (Developer Platform -> R2 sub-tab)",
        pageSource = PageSource.Url("https://www.cloudflare.com/plans/"),
        query = "How much free storage per month does Cloudflare R2 include on the free tier?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("10")),
        idealActionSequence = listOf(
            NavigationAction.Click::class,
            NavigationAction.Click::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 12
        )
    )

    /**
     * Developer Platform -> Pages sub-tab shows build limits. Free includes
     * 1 concurrent build; Paid includes 20 concurrent builds.
     * Ideal: click "Developer Platform" -> click "Pages" -> answer.
     */
    private fun cfDevPlatformPagesBuilds() = BenchmarkCase(
        id = "cf-dev-platform-pages-builds",
        description = "Cloudflare Pages free-tier concurrent builds (Developer Platform -> Pages sub-tab)",
        pageSource = PageSource.Url("https://www.cloudflare.com/plans/"),
        query = "How many concurrent builds does the Cloudflare Pages free tier include?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("1")),
        idealActionSequence = listOf(
            NavigationAction.Click::class,
            NavigationAction.Click::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 12
        )
    )

    // ---- Negative: information NOT on the page ----

    /**
     * Cloudflare Stream (video CDN) is a separate product with its own
     * pricing page. The /plans/ page only covers website protection plans
     * and Developer Platform.
     */
    private fun cfVideoStreaming() = BenchmarkCase(
        id = "cf-video-streaming",
        description = "Cloudflare video CDN/streaming pricing (not on plans page)",
        pageSource = PageSource.Url("https://www.cloudflare.com/plans/"),
        query = "What is the per-minute cost for Cloudflare Stream video encoding and delivery?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        idealActionSequence = listOf(
            NavigationAction.GiveUp::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
    )

    /**
     * Phone support is not available for Pro plan users. Only Enterprise
     * customers get dedicated support channels beyond standard email/ticket.
     */
    private fun cfPhoneSupportPro() = BenchmarkCase(
        id = "cf-phone-support-pro",
        description = "Cloudflare phone support for Pro plan (not available)",
        pageSource = PageSource.Url("https://www.cloudflare.com/plans/"),
        query = "What is the phone support number for Cloudflare Pro plan customers?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        idealActionSequence = listOf(
            NavigationAction.GiveUp::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
    )

    /**
     * Cloudflare does not offer email hosting as part of its plans page.
     * Email routing is a separate free feature but hosting is not listed here.
     */
    private fun cfEmailHosting() = BenchmarkCase(
        id = "cf-email-hosting",
        description = "Cloudflare email hosting pricing (not on plans page)",
        pageSource = PageSource.Url("https://www.cloudflare.com/plans/"),
        query = "How much does Cloudflare email hosting cost per mailbox per month?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        idealActionSequence = listOf(
            NavigationAction.GiveUp::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
    )

    /**
     * Dedicated IP addresses are not offered on the Cloudflare plans page.
     * Cloudflare uses shared Anycast IPs for all plans.
     */
    private fun cfDedicatedIp() = BenchmarkCase(
        id = "cf-dedicated-ip",
        description = "Cloudflare dedicated IP address pricing (does not exist)",
        pageSource = PageSource.Url("https://www.cloudflare.com/plans/"),
        query = "What is the monthly cost for a dedicated IP address on Cloudflare?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        idealActionSequence = listOf(
            NavigationAction.GiveUp::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
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
            NavigationAction.Click::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 2,
        constraints = BenchmarkConstraints(
            maxIterations = 14
        )
    )

    /**
     * Clicking "Developer Platform" tab changes the URL to /plans/developer-platform/.
     * Then "Workers KV" sub-tab must be selected to see KV limits. Free tier
     * includes 100K key-value reads per day.
     * Ideal: click "Developer Platform" -> click "Workers KV" -> answer.
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
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 12
        )
    )

    /**
     * On Developer Platform -> Cloudflare Workers sub-tab, the paid tier shows
     * "10 million requests/month included + $0.30 per additional million".
     * Ideal: click "Developer Platform" -> click "Workers" -> answer.
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
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
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
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 9
        )
    )

    // ==================== HubSpot Pricing (verified April 2026) ====================

    fun hubspot(): List<BenchmarkCase> = listOf(
        hubspotStarterPrice(),
        hubspotProfessionalPrice(),
        hubspotFreeToolsMention(),
        hubspotFaqAnnualDiscount(),
        hubspotFaqAdditionalContacts(),
        hubspotFaqCrmIncluded(),
        hubspotFaqEmailLimit(),
        hubspotFaqContactsEnterprise(),
        hubspotAeoTrialPrompts(),
        hubspotAeoIncludedTiers(),
        hubspotOnboardingFee(),
        hubspotPhoneSupportNumber(),
        hubspotFreeTrialDays(),
        hubspotSocialSchedulingLimit(),
        hubspotSalesforceNative()
    )

    // ---- Easy: visible plan pricing ----

    /**
     * The Starter plan price is visible in the plan cards near the top.
     * HubSpot Marketing Hub Starter starts at $20/month.
     * Ideal: read directly from plan cards -> answer.
     */
    private fun hubspotStarterPrice() = BenchmarkCase(
        id = "hubspot-starter-price",
        description = "HubSpot Marketing Hub Starter price (plan card)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "What is the starting price for HubSpot Marketing Hub Starter?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("20")),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 2,
        constraints = BenchmarkConstraints(
            maxIterations = 7
        )
    )

    /**
     * Professional plan price is in the plan cards: starts at $890/month.
     * Ideal: scroll to Professional card -> answer.
     */
    private fun hubspotProfessionalPrice() = BenchmarkCase(
        id = "hubspot-professional-price",
        description = "HubSpot Marketing Hub Professional starting price (plan card)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "What is the starting price for HubSpot Marketing Hub Professional?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("890")),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 2,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
    )

    /**
     * The page references "Free tools" available without a paid plan.
     * Ideal: scroll slightly -> answer.
     */
    private fun hubspotFreeToolsMention() = BenchmarkCase(
        id = "hubspot-free-tools",
        description = "HubSpot free marketing tools availability (visible on page)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "Does HubSpot offer any free marketing tools without a paid plan?",
        expectedOutcome = ExpectedOutcome.AnswerContains(
            listOf("free"), caseSensitive = false
        ),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 9
        )
    )

    // ---- Medium: FAQ accordions ----

    /**
     * FAQ accordion: "Do I get a discount for an annual commitment?" details
     * the discount percentage or savings for paying annually vs monthly.
     * Ideal: scroll to FAQ -> click accordion -> answer.
     */
    private fun hubspotFaqAnnualDiscount() = BenchmarkCase(
        id = "hubspot-faq-annual-discount",
        description = "HubSpot FAQ: annual commitment discount (accordion)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "According to HubSpot's FAQ, do you get a discount for committing to an annual plan?",
        expectedOutcome = ExpectedOutcome.AnswerContains(
            listOf("annual"), caseSensitive = false
        ),
        idealActionSequence = listOf(
            NavigationAction.Click::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 4,
        constraints = BenchmarkConstraints(
            maxIterations = 11
        )
    )

    /**
     * FAQ accordion: "Can I buy additional contacts?" explains the pricing
     * for additional marketing contacts beyond what's included in each tier.
     * Ideal: scroll to FAQ -> click accordion -> answer.
     */
    private fun hubspotFaqAdditionalContacts() = BenchmarkCase(
        id = "hubspot-faq-additional-contacts",
        description = "HubSpot FAQ: additional contacts pricing (accordion)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "According to HubSpot's FAQ, can you purchase additional marketing contacts beyond what's included?",
        expectedOutcome = ExpectedOutcome.AnswerContains(
            listOf("contact"), caseSensitive = false
        ),
        idealActionSequence = listOf(
            NavigationAction.Click::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 4,
        constraints = BenchmarkConstraints(
            maxIterations = 11
        )
    )

    /**
     * FAQ accordion: "Does Marketing Hub include CRM?" confirms that
     * HubSpot's free CRM is included with all Marketing Hub plans.
     * Ideal: scroll to FAQ -> click accordion -> answer.
     */
    private fun hubspotFaqCrmIncluded() = BenchmarkCase(
        id = "hubspot-faq-crm-included",
        description = "HubSpot FAQ: CRM included with Marketing Hub (accordion)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "According to HubSpot's FAQ, is the CRM included with Marketing Hub?",
        expectedOutcome = ExpectedOutcome.AnswerContains(
            listOf("CRM", "included"), caseSensitive = false
        ),
        idealActionSequence = listOf(
            NavigationAction.Click::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 4,
        constraints = BenchmarkConstraints(
            maxIterations = 11
        )
    )

    // ---- Negative: information NOT on the page ----

    /**
     * HubSpot does not list a direct phone support number on the Marketing
     * Hub pricing page. Support channels are described elsewhere.
     */
    private fun hubspotPhoneSupportNumber() = BenchmarkCase(
        id = "hubspot-phone-support-number",
        description = "HubSpot direct phone support number (not on pricing page)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "What is HubSpot's direct phone support number for Marketing Hub customers?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        idealActionSequence = listOf(
            NavigationAction.GiveUp::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
    )

    /**
     * The pricing page does not specify the exact number of days for a free
     * trial of Marketing Hub (if one is offered at all).
     */
    private fun hubspotFreeTrialDays() = BenchmarkCase(
        id = "hubspot-free-trial-days",
        description = "HubSpot Marketing Hub free trial duration in days (not specified)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "Exactly how many days is the HubSpot Marketing Hub Professional free trial?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        idealActionSequence = listOf(
            NavigationAction.GiveUp::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
    )

    /**
     * Social media scheduling post limits are not detailed on the Marketing
     * Hub pricing page — they would be in product documentation.
     */
    private fun hubspotSocialSchedulingLimit() = BenchmarkCase(
        id = "hubspot-social-scheduling-limit",
        description = "HubSpot social media scheduling post limits (not on pricing page)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "How many social media posts per month can be scheduled with HubSpot Marketing Hub Professional?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        idealActionSequence = listOf(
            NavigationAction.GiveUp::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
    )

    /**
     * Salesforce integration details and pricing are on the Sales Hub or
     * integrations page, not on the Marketing Hub pricing page.
     */
    private fun hubspotSalesforceNative() = BenchmarkCase(
        id = "hubspot-salesforce-native",
        description = "HubSpot native Salesforce connector pricing (not on this page)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "What is the price of HubSpot's native Salesforce integration add-on for Marketing Hub?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        idealActionSequence = listOf(
            NavigationAction.GiveUp::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
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
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 10
        )
    )

    // ==================== GitHub Pricing (verified April 2026) ====================

    fun github(): List<BenchmarkCase> = listOf(
        ghFreePrice(),
        ghTeamPrice(),
        ghEnterprisePrice(),
        ghPackagesStorageTeam(),
        ghSamlSsoAvailability(),
        ghPremiumSupportSla(),
        ghCodespaces4CoreHours(),
        ghActionsMinutesTeam(),
        ghDataResidencyCloud(),
        ghCodespacesFreeHours(),
        ghCopilotFreeLimits(),
        ghLfsPrice(),
        ghOnPremisePrice(),
        ghPhoneSupportTeam(),
        ghBitbucketMigration(),
        ghAiCodeReview()
    )

    // ---- Easy: visible plan cards ----

    /**
     * Free plan pricing is prominently shown: "$0 USD per month".
     * Ideal: read directly -> answer.
     */
    private fun ghFreePrice() = BenchmarkCase(
        id = "gh-free-price",
        description = "GitHub Free plan price (plan card near top)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "What does the GitHub Free plan cost per month?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("0")),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 2,
        constraints = BenchmarkConstraints(
            maxIterations = 7
        )
    )

    /**
     * Team plan shows "$4 USD per user/month" in the plan card.
     * Ideal: scroll to Team card -> answer.
     */
    private fun ghTeamPrice() = BenchmarkCase(
        id = "gh-team-price",
        description = "GitHub Team plan price per user (plan card)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "What is the GitHub Team plan price per user per month?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("4")),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 2,
        constraints = BenchmarkConstraints(
            maxIterations = 7
        )
    )

    /**
     * Enterprise plan starts at "$21 USD per user/month" in the plan card.
     * Ideal: scroll to Enterprise card -> answer.
     */
    private fun ghEnterprisePrice() = BenchmarkCase(
        id = "gh-enterprise-price",
        description = "GitHub Enterprise starting price per user (plan card)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "What is the starting price for GitHub Enterprise per user per month?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("21")),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 2,
        constraints = BenchmarkConstraints(
            maxIterations = 7
        )
    )

    /**
     * GitHub Packages storage for Team is 2GB (Free for public repos).
     * Visible in the Team plan card features list.
     * Ideal: scroll to Team card -> answer.
     */
    private fun ghPackagesStorageTeam() = BenchmarkCase(
        id = "gh-packages-storage-team",
        description = "GitHub Packages storage for Team plan (plan card feature)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "How much Packages storage does the GitHub Team plan include?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("2GB")),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
    )

    // ---- Medium: comparison table rows ----

    /**
     * SAML SSO is in the comparison table under "Platform security and
     * compliance". Only Enterprise has it. Requires scrolling deep into the
     * comparison section.
     * Ideal: scroll to comparison -> find SAML row -> answer.
     */
    private fun ghSamlSsoAvailability() = BenchmarkCase(
        id = "gh-saml-sso",
        description = "GitHub SAML SSO plan availability (deep comparison table)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "Which GitHub plans include SAML single sign-on (SSO)?",
        expectedOutcome = ExpectedOutcome.AnswerContains(
            listOf("enterprise"), caseSensitive = false
        ),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 10
        )
    )

    /**
     * Premium Support details mention "30-minute SLA on Urgent tickets and
     * 24/7 web and phone support via callback request". This is in the add-on
     * section or deep in the comparison table's Support section.
     * Ideal: scroll to Support section -> answer.
     */
    private fun ghPremiumSupportSla() = BenchmarkCase(
        id = "gh-premium-support-sla",
        description = "GitHub Premium Support SLA for urgent tickets (deep comparison)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "What is the SLA response time for urgent tickets with GitHub Premium Support?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("30-minute", "30 minute")),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 10
        )
    )

    // ---- Hard: expandable rows with embedded content ----

    /**
     * In the Codespaces comparison row, the expandable description mentions
     * "On a 4-core machine, you would get 30 hours free". Requires clicking
     * the row to expand and then reading the explanatory text.
     * Ideal: scroll to Codespaces in comparison -> click expand -> answer.
     */
    private fun ghCodespaces4CoreHours() = BenchmarkCase(
        id = "gh-codespaces-4core-hours",
        description = "GitHub Codespaces free hours for 4-core (expandable row, different from 2-core)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "How many free hours does GitHub Codespaces give on a 4-core machine?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("30")),
        idealActionSequence = listOf(
            NavigationAction.Click::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 4,
        constraints = BenchmarkConstraints(
            maxIterations = 12
        )
    )

    // ---- Negative: information NOT on the page ----

    /**
     * GitHub Enterprise Server (on-premise) pricing is NOT on the public
     * /pricing page. The page mentions Enterprise Cloud but GHES pricing
     * requires contacting sales separately.
     */
    private fun ghOnPremisePrice() = BenchmarkCase(
        id = "gh-on-premise-price",
        description = "GitHub Enterprise Server on-premise pricing (not on this page)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "What is the per-user price for GitHub Enterprise Server (self-hosted/on-premise)?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        idealActionSequence = listOf(
            NavigationAction.GiveUp::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
    )

    /**
     * Phone support is only available with Premium/Premium Plus, not with
     * the Team plan. The Team plan only has "Web-based support".
     */
    private fun ghPhoneSupportTeam() = BenchmarkCase(
        id = "gh-phone-support-team",
        description = "GitHub phone support for Team plan (not available)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "What is the phone number for GitHub Team plan phone support?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        idealActionSequence = listOf(
            NavigationAction.GiveUp::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
    )

    /**
     * GitHub does not offer a BitBucket migration tool or pricing for it
     * on the pricing page.
     */
    private fun ghBitbucketMigration() = BenchmarkCase(
        id = "gh-bitbucket-migration",
        description = "GitHub BitBucket migration tool pricing (does not exist on page)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "What does GitHub charge for the automated BitBucket repository migration tool?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        idealActionSequence = listOf(
            NavigationAction.GiveUp::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
    )

    /**
     * AI-powered code review as a standalone product/add-on is not listed
     * on the pricing page. Copilot Autofix is in Code Security but not as
     * a separately priced "AI code review" add-on.
     */
    private fun ghAiCodeReview() = BenchmarkCase(
        id = "gh-ai-code-review",
        description = "GitHub AI code review add-on pricing (not a listed product)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "How much does GitHub's dedicated AI Code Review add-on cost per repository per month?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        idealActionSequence = listOf(
            NavigationAction.GiveUp::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
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
            NavigationAction.Click::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 2,
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
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 10
        )
    )

    // ==================== Intercom Pricing (verified May 2026) ====================

    fun intercom(): List<BenchmarkCase> = listOf(
        intercomEssentialSeatPrice(),
        intercomAdvancedSeatPrice(),
        intercomExpertSeatPrice(),
        intercomFinOutcomePrice(),
        intercomProAddonPrice(),
        intercomCopilotAddonPrice(),
        intercomProactiveSupportPlus(),
        intercomFaqPricingComponents(),
        intercomAdvancedLiteSeats(),
        intercomExpertSlaFeature(),
        intercomExpertHipaa(),
        intercomFaqMinimumStart(),
        intercomPhoneSupportNumber(),
        intercomApiRateLimits(),
        intercomFreePlan()
    )

    // ---- Easy: visible plan card pricing ----

    /**
     * Essential plan shows "$29 per seat/mo" prominently in the plan card.
     * Ideal: read directly from plan cards -> answer.
     */
    private fun intercomEssentialSeatPrice() = BenchmarkCase(
        id = "intercom-essential-seat",
        description = "Intercom Essential plan seat price (plan card near top)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "What is the Intercom Essential plan price per seat per month?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("29")),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 2,
        constraints = BenchmarkConstraints(
            maxIterations = 7
        )
    )

    /**
     * Advanced plan shows "$85 per seat/mo" in the plan card.
     * Ideal: scroll to Advanced card -> answer.
     */
    private fun intercomAdvancedSeatPrice() = BenchmarkCase(
        id = "intercom-advanced-seat",
        description = "Intercom Advanced plan seat price (plan card)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "What is the Intercom Advanced plan price per seat per month?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("85")),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 2,
        constraints = BenchmarkConstraints(
            maxIterations = 7
        )
    )

    /**
     * Expert plan shows "$132 per seat/mo" in the plan card.
     * Ideal: scroll to Expert card -> answer.
     */
    private fun intercomExpertSeatPrice() = BenchmarkCase(
        id = "intercom-expert-seat",
        description = "Intercom Expert plan seat price (plan card)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "What is the Intercom Expert plan price per seat per month?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("132")),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 2,
        constraints = BenchmarkConstraints(
            maxIterations = 7
        )
    )

    /**
     * Fin AI Agent per-outcome price is shown across all plans: "From $0.99
     * per Fin outcome".
     * Ideal: read from plan cards -> answer.
     */
    private fun intercomFinOutcomePrice() = BenchmarkCase(
        id = "intercom-fin-outcome-price",
        description = "Intercom Fin AI Agent per-outcome price (all plan cards)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "How much does Intercom charge per Fin AI Agent outcome?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("0.99")),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 2,
        constraints = BenchmarkConstraints(
            maxIterations = 7
        )
    )

    // ---- Medium: add-on section and plan feature details ----

    /**
     * The Pro add-on is in the "Add-ons" section below the plan cards:
     * "$99/mo includes analysis of 1,000 conversations/mo".
     * Ideal: scroll to Add-ons section -> answer.
     */
    private fun intercomProAddonPrice() = BenchmarkCase(
        id = "intercom-pro-addon",
        description = "Intercom Pro add-on price and included conversations (Add-ons section)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "What is the price of Intercom's Pro add-on and how many conversations does it include?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("99", "1,000")),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 9
        )
    )

    /**
     * The Copilot add-on is "$29 per agent/mo" for unlimited usage.
     * Ideal: scroll to Add-ons section -> answer.
     */
    private fun intercomCopilotAddonPrice() = BenchmarkCase(
        id = "intercom-copilot-addon",
        description = "Intercom Copilot add-on price per agent (Add-ons section)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "What is the per-agent monthly price of the Intercom Copilot add-on?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("29")),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 9
        )
    )

    /**
     * Proactive Support Plus is "$99/mo" and "includes 500 messages sent/mo".
     * In the Add-ons section, requires scrolling past Pro and Copilot.
     * Ideal: scroll to Add-ons section -> answer.
     */
    private fun intercomProactiveSupportPlus() = BenchmarkCase(
        id = "intercom-proactive-support-plus",
        description = "Intercom Proactive Support Plus price and included messages (Add-ons)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "What is Intercom's Proactive Support Plus add-on price and how many messages does it include?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("99", "500")),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 9
        )
    )

    /**
     * FAQ accordion: "How does Intercom pricing work?" explains the two
     * components: Seats (per teammate based on plan) and Usage (Fin outcomes,
     * messaging channels).
     * Ideal: scroll to FAQ -> click accordion -> answer.
     */
    private fun intercomFaqPricingComponents() = BenchmarkCase(
        id = "intercom-faq-pricing-components",
        description = "Intercom FAQ: pricing components seats + usage (accordion)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "According to Intercom's FAQ, what are the two main components of Intercom pricing?",
        expectedOutcome = ExpectedOutcome.AnswerContains(
            listOf("seats", "usage"), caseSensitive = false
        ),
        idealActionSequence = listOf(
            NavigationAction.Click::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 4,
        constraints = BenchmarkConstraints(
            maxIterations = 11
        )
    )

    /**
     * The Advanced plan includes "20 free Lite seats" in the feature list.
     * This is a specific detail within the plan card's feature bullets.
     * Ideal: scroll to Advanced plan features -> answer.
     */
    private fun intercomAdvancedLiteSeats() = BenchmarkCase(
        id = "intercom-advanced-lite-seats",
        description = "Intercom Advanced plan included free Lite seats (plan feature bullet)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "How many free Lite seats are included with the Intercom Advanced plan?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("20")),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 9
        )
    )

    // ---- Hard: deep plan feature details ----

    /**
     * SLAs (Service Level Agreements) are listed only in the Expert plan's
     * feature list. Requires scrolling to the Expert card and reading its
     * detailed feature bullets (distinct from Essential and Advanced).
     * Ideal: scroll to Expert features -> answer.
     */
    private fun intercomExpertSlaFeature() = BenchmarkCase(
        id = "intercom-expert-sla",
        description = "Intercom Expert plan SLA feature (deep plan feature list)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "Which Intercom plan includes Service Level Agreements (SLAs)?",
        expectedOutcome = ExpectedOutcome.AnswerContains(
            listOf("expert"), caseSensitive = false
        ),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 9
        )
    )

    /**
     * HIPAA support is only available on the Expert plan. Listed in the
     * Expert plan features but not in Essential or Advanced.
     * Ideal: scroll to Expert features -> answer.
     */
    private fun intercomExpertHipaa() = BenchmarkCase(
        id = "intercom-expert-hipaa",
        description = "Intercom HIPAA support plan availability (Expert only)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "Which Intercom plan includes HIPAA compliance support?",
        expectedOutcome = ExpectedOutcome.AnswerContains(
            listOf("expert"), caseSensitive = false
        ),
        idealActionSequence = listOf(
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 9
        )
    )

    /**
     * FAQ accordion: "What's the minimum to get started?" is a collapsed
     * FAQ item. The answer explains minimum requirements.
     * Ideal: scroll to FAQ -> click accordion -> answer.
     */
    private fun intercomFaqMinimumStart() = BenchmarkCase(
        id = "intercom-faq-minimum-start",
        description = "Intercom FAQ: minimum to get started (accordion)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "According to Intercom's FAQ, what is the minimum required to get started with Intercom?",
        expectedOutcome = ExpectedOutcome.AnswerContains(
            listOf("seat"), caseSensitive = false
        ),
        idealActionSequence = listOf(
            NavigationAction.Click::class,
            NavigationAction.ExplorationFinished::class
        ),
        optimalIterations = 4,
        constraints = BenchmarkConstraints(
            maxIterations = 11
        )
    )

    // ---- Negative: information NOT on the page ----

    /**
     * Intercom does not list a direct phone support number on its pricing
     * page. Support channels are described on a separate help page.
     */
    private fun intercomPhoneSupportNumber() = BenchmarkCase(
        id = "intercom-phone-support-number",
        description = "Intercom direct phone support number (not on pricing page)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "What is Intercom's direct phone support number for customers?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        idealActionSequence = listOf(
            NavigationAction.GiveUp::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
    )

    /**
     * API rate limits are not mentioned on the pricing page. They would
     * be in developer documentation.
     */
    private fun intercomApiRateLimits() = BenchmarkCase(
        id = "intercom-api-rate-limits",
        description = "Intercom API rate limits (not on pricing page)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "What are the API rate limits per plan on Intercom's pricing page?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        idealActionSequence = listOf(
            NavigationAction.GiveUp::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
    )

    /**
     * Intercom does not offer a free plan. All plans require at minimum
     * $29/seat/month (Essential). The page only shows paid tiers.
     */
    private fun intercomFreePlan() = BenchmarkCase(
        id = "intercom-free-plan",
        description = "Intercom free plan/tier (does not exist)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "What features are included in Intercom's free plan with no payment required?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        idealActionSequence = listOf(
            NavigationAction.GiveUp::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
    )
}
