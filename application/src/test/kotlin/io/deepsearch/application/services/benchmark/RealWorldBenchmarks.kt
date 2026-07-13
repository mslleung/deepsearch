package io.deepsearch.application.services.benchmark

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

    fun otpWellWomanGold(): List<BenchmarkCase> = listOf(otpWellWomanGoldPrice())
    fun otpHepC(): List<BenchmarkCase> = listOf(otpHepCAccordion())
    fun stripeRadarFraud(): List<BenchmarkCase> = listOf(stripeRadarFraudTeamsCustom())
    fun stripeFaqDiscount(): List<BenchmarkCase> = listOf(stripeFaqDiscounts())
    fun notionStudentDiscount(): List<BenchmarkCase> = listOf(notionFaqStudentDiscount())
    fun notionRefundPolicy(): List<BenchmarkCase> = listOf(notionFaqRefundPolicy())

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

    private fun sfProAiPrice() = BenchmarkCase(
        id = "sf-pro-ai-price",
        description = "SleekFlow Pro AI plan price (near top)",
        pageSource = PageSource.Url("https://sleekflow.io/pricing"),
        query = "What is the price of the SleekFlow Pro AI plan?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions the Pro AI plan price of approximately $99 or 99"),
        constraints = BenchmarkConstraints(maxIterations = 7)
    )

    private fun sfPremiumAiPrice() = BenchmarkCase(
        id = "sf-premium-ai-price",
        description = "SleekFlow Premium AI plan price (2-3 scrolls)",
        pageSource = PageSource.Url("https://sleekflow.io/pricing"),
        query = "What is the price of the SleekFlow Premium AI plan?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions the Premium AI plan price of approximately $299 or 299"),
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    private fun sfRoleBasedAccessControl() = BenchmarkCase(
        id = "sf-rbac",
        description = "SleekFlow RBAC plan availability (deep in feature grid)",
        pageSource = PageSource.Url("https://sleekflow.io/pricing"),
        query = "Which SleekFlow plans include role-based access control?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions that Premium and/or Enterprise plans include role-based access control"),
        constraints = BenchmarkConstraints(maxIterations = 10)
    )

    private fun sfFaqActiveContact() = BenchmarkCase(
        id = "sf-faq-active-contact",
        description = "SleekFlow FAQ: what counts as active contact (accordion)",
        pageSource = PageSource.Url("https://sleekflow.io/pricing"),
        query = "According to SleekFlow's FAQ, what counts as a monthly active contact?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer explains what counts as a monthly active contact, mentioning chat or conversation activity"),
        constraints = BenchmarkConstraints(maxIterations = 10)
    )

    private fun sfMonthlyActiveContacts() = BenchmarkCase(
        id = "sf-macs-per-plan",
        description = "SleekFlow MACs per plan (feature comparison grid)",
        pageSource = PageSource.Url("https://sleekflow.io/pricing"),
        query = "How many monthly active contacts are included in each SleekFlow plan?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions specific contact limits per plan, including values like 50 and 500"),
        constraints = BenchmarkConstraints(maxIterations = 9)
    )

    private fun sfSalesforceIntegration() = BenchmarkCase(
        id = "sf-salesforce",
        description = "SleekFlow Salesforce integration availability",
        pageSource = PageSource.Url("https://sleekflow.io/pricing"),
        query = "Which SleekFlow plans include Salesforce integration?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions that Enterprise plan includes Salesforce integration"),
        constraints = BenchmarkConstraints(maxIterations = 9)
    )

    // ==================== OT&P Healthcare ====================

    private fun otpStandardPrice() = BenchmarkCase(
        id = "otp-standard-price",
        description = "OT&P Standard body check price",
        pageSource = PageSource.Url("https://www.otandp.com/body-check/"),
        query = "What is the price of the OT&P Standard body check package?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions the Standard package price of approximately HK\$5,900 or 5900"),
        constraints = BenchmarkConstraints(maxIterations = 9)
    )

    private fun otpUltraPrice() = BenchmarkCase(
        id = "otp-ultra-price",
        description = "OT&P Ultra body check price",
        pageSource = PageSource.Url("https://www.otandp.com/body-check/"),
        query = "What is the price of the OT&P Ultra body check package?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions the Ultra package price of approximately HK\$15,900 or 15900"),
        constraints = BenchmarkConstraints(maxIterations = 9)
    )

    private fun otpStressTest() = BenchmarkCase(
        id = "otp-stress-test",
        description = "OT&P which package includes Stress Test",
        pageSource = PageSource.Url("https://www.otandp.com/body-check/"),
        query = "Which OT&P body check package includes a Stress Test (treadmill test)?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions that the Ultra package includes a Stress Test or treadmill test"),
        constraints = BenchmarkConstraints(maxIterations = 9)
    )

    private fun otpCardiovascularPrice() = BenchmarkCase(
        id = "otp-cardiovascular-price",
        description = "OT&P Cardiovascular Risk Package price (specialised page)",
        pageSource = PageSource.Url("https://www.otandp.com/body-check/specialised-health-checks"),
        query = "What is the price of the OT&P Cardiovascular Risk Package?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions the Cardiovascular Risk Package price of approximately HK\$4,900 or 4900"),
        constraints = BenchmarkConstraints(maxIterations = 9)
    )

    private fun otpWellWomanGoldPrice() = BenchmarkCase(
        id = "otp-wellwoman-gold",
        description = "OT&P Well Woman Gold package price",
        pageSource = PageSource.Url("https://www.otandp.com/body-check/specialised-health-checks"),
        query = "What is the price of the OT&P Well Woman Gold package?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions the Well Woman Gold package price of approximately HK\$9,900 or 9900"),
        constraints = BenchmarkConstraints(maxIterations = 9)
    )

    private fun otpHepCAccordion() = BenchmarkCase(
        id = "otp-hep-c-accordion",
        description = "OT&P Hep C Ab inclusion (deep accordion table)",
        pageSource = PageSource.Url("https://www.otandp.com/body-check/"),
        query = "Which OT&P body check packages include Hepatitis C Antibody (Hep C Ab) testing?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions that the Ultra package includes Hepatitis C Antibody testing"),
        constraints = BenchmarkConstraints(maxIterations = 12)
    )

    private fun otpHomocysteineAccordion() = BenchmarkCase(
        id = "otp-homocysteine-accordion",
        description = "OT&P Homocysteine inclusion (accordion cardiovascular sub-section)",
        pageSource = PageSource.Url("https://www.otandp.com/body-check/"),
        query = "Which OT&P body check packages include Homocysteine testing?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions that the Ultra package includes Homocysteine testing"),
        constraints = BenchmarkConstraints(maxIterations = 12)
    )

    private fun otpFaqHealthCheckDefinition() = BenchmarkCase(
        id = "otp-faq-health-check-definition",
        description = "OT&P body-check FAQ: what is a health check (accordion)",
        pageSource = PageSource.Url("https://www.otandp.com/body-check/"),
        query = "According to OT&P's body check page FAQ, what is a health check?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer describes a health check as a comprehensive evaluation or assessment of one's health"),
        constraints = BenchmarkConstraints(maxIterations = 12)
    )

    private fun otpFitAtFiftyPrice() = BenchmarkCase(
        id = "otp-fit-at-fifty-price",
        description = "OT&P Fit at Fifty price (hidden tab)",
        pageSource = PageSource.Url("https://www.otandp.com/body-check/specialised-health-checks"),
        query = "What is the price of the OT&P Fit at Fifty package?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions the Fit at Fifty package price of approximately HK\$7,900 or 7900"),
        constraints = BenchmarkConstraints(maxIterations = 11)
    )

    private fun otpCancerRiskPrice() = BenchmarkCase(
        id = "otp-cancer-risk-price",
        description = "OT&P Cancer Risk Package price (hidden tab)",
        pageSource = PageSource.Url("https://www.otandp.com/body-check/specialised-health-checks"),
        query = "What is the price of the OT&P Cancer Risk Package?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions the Cancer Risk Package price of approximately HK\$6,900 or 6900"),
        constraints = BenchmarkConstraints(maxIterations = 11)
    )

    private fun otpCardiovascularStressTest() = BenchmarkCase(
        id = "otp-cardiovascular-stress-test",
        description = "OT&P Cardiovascular Risk Package includes Stress Test (tab content)",
        pageSource = PageSource.Url("https://www.otandp.com/body-check/specialised-health-checks"),
        query = "Does the OT&P Cardiovascular Risk Package include a Stress Test?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer confirms the Cardiovascular Risk Package includes a Stress Test or treadmill test"),
        constraints = BenchmarkConstraints(maxIterations = 11)
    )

    private fun otpFaqFirstVisitDuration() = BenchmarkCase(
        id = "otp-faq-first-visit-duration",
        description = "OT&P FAQ: first-time GP consultation length (accordion)",
        pageSource = PageSource.Url("https://www.otandp.com/faq/"),
        query = "According to OT&P's FAQ, how long is a first-time GP consultation?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions approximately 30 minutes for a first-time GP consultation"),
        constraints = BenchmarkConstraints(maxIterations = 11)
    )

    private fun otpFaqFemaleDoctors() = BenchmarkCase(
        id = "otp-faq-female-doctors",
        description = "OT&P FAQ: clinics with female doctors for Well Woman (accordion)",
        pageSource = PageSource.Url("https://www.otandp.com/faq/"),
        query = "At which OT&P clinics can you see a female doctor for a Well Woman check?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions Central, Repulse Bay, and Clearwater Bay clinics as locations with female doctors"),
        constraints = BenchmarkConstraints(maxIterations = 11)
    )

    private fun otpCentralClinicAddress() = BenchmarkCase(
        id = "otp-central-clinic-address",
        description = "OT&P Central GP clinic address (multi-pane contact page)",
        pageSource = PageSource.Url("https://www.otandp.com/contact"),
        query = "What is the address of the OT&P Central General Practice Clinic?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer includes D'Aguilar Street and/or Century Square as part of the Central clinic address"),
        constraints = BenchmarkConstraints(maxIterations = 10)
    )

    private fun otpAboutFounded() = BenchmarkCase(
        id = "otp-about-founded",
        description = "OT&P founding year and founders (static about page)",
        pageSource = PageSource.Url("https://www.otandp.com/about"),
        query = "When was OT&P founded and who were the founders?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions OT&P was founded in 1994 and names Dr Trodd and Dr Owens as founders"),
        constraints = BenchmarkConstraints(maxIterations = 9)
    )

    private fun otpNonexistentPediatric() = BenchmarkCase(
        id = "otp-nonexistent-pediatric",
        description = "OT&P Pediatric Health Check (does not exist)",
        pageSource = PageSource.Url("https://www.otandp.com/body-check/"),
        query = "What is the price of the OT&P Pediatric Health Check package?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    private fun otpFaqTelemedicine() = BenchmarkCase(
        id = "otp-faq-telemedicine",
        description = "OT&P FAQ: telemedicine/virtual consultations (not on page)",
        pageSource = PageSource.Url("https://www.otandp.com/faq/"),
        query = "Does OT&P offer telemedicine or virtual consultation services?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    private fun otpAboutIpo() = BenchmarkCase(
        id = "otp-about-ipo",
        description = "OT&P IPO date (does not exist — private company)",
        pageSource = PageSource.Url("https://www.otandp.com/about"),
        query = "When did OT&P Healthcare go public and what was the IPO price?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        constraints = BenchmarkConstraints(maxIterations = 6)
    )

    private fun otpSpecialisedDermatology() = BenchmarkCase(
        id = "otp-specialised-dermatology",
        description = "OT&P Dermatology Screening (does not exist)",
        pageSource = PageSource.Url("https://www.otandp.com/body-check/specialised-health-checks"),
        query = "What is the price of the OT&P Dermatology Screening Package?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    private fun otpBodycheckMri() = BenchmarkCase(
        id = "otp-bodycheck-mri",
        description = "OT&P MRI scan inclusion (does not exist)",
        pageSource = PageSource.Url("https://www.otandp.com/body-check/"),
        query = "Which OT&P body check packages include an MRI scan?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    // ==================== Stripe Pricing ====================

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

    private fun stripeDomesticCardRate() = BenchmarkCase(
        id = "stripe-domestic-card-rate",
        description = "Stripe standard domestic card processing rate (headline pricing)",
        pageSource = PageSource.Url("https://stripe.com/us/pricing"),
        query = "What is Stripe's standard processing fee for domestic card transactions?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions 2.9% + 30 cents per successful transaction for domestic cards"),
        constraints = BenchmarkConstraints(maxIterations = 7)
    )

    private fun stripeInternationalSurcharge() = BenchmarkCase(
        id = "stripe-international-surcharge",
        description = "Stripe international card surcharge (near top)",
        pageSource = PageSource.Url("https://stripe.com/us/pricing"),
        query = "What additional fee does Stripe charge for international card transactions?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions an additional 1.5% surcharge for international cards"),
        constraints = BenchmarkConstraints(maxIterations = 7)
    )

    private fun stripeAchDebitRate() = BenchmarkCase(
        id = "stripe-ach-debit-rate",
        description = "Stripe ACH Direct Debit rate and cap (Payments section)",
        pageSource = PageSource.Url("https://stripe.com/us/pricing"),
        query = "What is Stripe's fee for ACH Direct Debit transactions and what is the maximum fee?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions 0.8% for ACH Direct Debit with a $5.00 cap"),
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    private fun stripeTerminalDomesticRate() = BenchmarkCase(
        id = "stripe-terminal-domestic-rate",
        description = "Stripe Terminal in-person domestic card rate (mid-page)",
        pageSource = PageSource.Url("https://stripe.com/us/pricing"),
        query = "What is Stripe Terminal's processing fee for in-person domestic card transactions?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions 2.7% + 5 cents per successful transaction for in-person domestic cards"),
        constraints = BenchmarkConstraints(maxIterations = 9)
    )

    private fun stripeBillingUsageRate() = BenchmarkCase(
        id = "stripe-billing-usage-rate",
        description = "Stripe Billing usage-based rate (expandable section)",
        pageSource = PageSource.Url("https://stripe.com/us/pricing"),
        query = "What is Stripe's usage-based Billing fee as a percentage of volume?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions 0.7% of Billing volume"),
        constraints = BenchmarkConstraints(maxIterations = 10)
    )

    private fun stripeTaxApiPrice() = BenchmarkCase(
        id = "stripe-tax-api-price",
        description = "Stripe Tax API per-transaction price (expandable Tax section)",
        pageSource = PageSource.Url("https://stripe.com/us/pricing"),
        query = "What does Stripe Tax charge per transaction for API integrations?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions $0.50 per transaction for Stripe Tax API integrations"),
        constraints = BenchmarkConstraints(maxIterations = 11)
    )

    private fun stripeAtlasFee() = BenchmarkCase(
        id = "stripe-atlas-fee",
        description = "Stripe Atlas incorporation one-time fee (deep scroll)",
        pageSource = PageSource.Url("https://stripe.com/us/pricing"),
        query = "What is the one-time setup fee for Stripe Atlas incorporation?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions a $500 one-time setup fee for Stripe Atlas"),
        constraints = BenchmarkConstraints(maxIterations = 10)
    )

    private fun stripeRadarFraudTeamsCustom() = BenchmarkCase(
        id = "stripe-radar-fraud-custom",
        description = "Stripe Radar for Fraud Teams custom pricing per transaction (More features)",
        pageSource = PageSource.Url("https://stripe.com/us/pricing"),
        query = "What does Stripe Radar for Fraud Teams charge per screened transaction for accounts with custom pricing?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions $0.07 per screened transaction for Radar for Fraud Teams with custom pricing"),
        constraints = BenchmarkConstraints(maxIterations = 12)
    )

    private fun stripeIssuingVirtualCard() = BenchmarkCase(
        id = "stripe-issuing-virtual-card",
        description = "Stripe Issuing virtual card price (deep Money Management section)",
        pageSource = PageSource.Url("https://stripe.com/us/pricing"),
        query = "How much does Stripe charge per virtual card issued?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions $0.10 per virtual card issued"),
        constraints = BenchmarkConstraints(maxIterations = 10)
    )

    private fun stripeFreePlan() = BenchmarkCase(
        id = "stripe-free-plan",
        description = "Stripe free plan/tier (does not exist)",
        pageSource = PageSource.Url("https://stripe.com/us/pricing"),
        query = "What is included in Stripe's free plan and how many free transactions per month does it offer?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    private fun stripePhoneSupport() = BenchmarkCase(
        id = "stripe-phone-support",
        description = "Stripe phone support for standard users (not available)",
        pageSource = PageSource.Url("https://stripe.com/us/pricing"),
        query = "What is the phone number for Stripe standard-pricing customer support?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    private fun stripeEscrowService() = BenchmarkCase(
        id = "stripe-escrow-service",
        description = "Stripe escrow/held-funds service (does not exist)",
        pageSource = PageSource.Url("https://stripe.com/us/pricing"),
        query = "What is the fee for Stripe's escrow service that holds funds between buyer and seller?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    private fun stripeChargebackInsurance() = BenchmarkCase(
        id = "stripe-chargeback-insurance",
        description = "Stripe chargeback insurance/guarantee (does not exist)",
        pageSource = PageSource.Url("https://stripe.com/us/pricing"),
        query = "How much does Stripe's chargeback insurance cost to guarantee against all dispute losses?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    private fun stripeFaqSetupFees() = BenchmarkCase(
        id = "stripe-faq-setup-fees",
        description = "Stripe FAQ: setup/monthly/closure fees (accordion)",
        pageSource = PageSource.Url("https://stripe.com/us/pricing"),
        query = "According to Stripe's FAQ, does Stripe charge setup fees, monthly fees, or closure fees?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer states that Stripe does not charge setup fees or monthly fees"),
        constraints = BenchmarkConstraints(maxIterations = 11)
    )

    private fun stripeFaqRefundStandard() = BenchmarkCase(
        id = "stripe-faq-refund-standard",
        description = "Stripe FAQ: refund fees for standard pricing (multi-bullet accordion)",
        pageSource = PageSource.Url("https://stripe.com/us/pricing"),
        query = "According to Stripe's FAQ, are there fees for refunds on standard pricing for non-bank-transfer payments?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer addresses refund fees for standard pricing, explaining the policy for non-bank-transfer payments"),
        constraints = BenchmarkConstraints(maxIterations = 11)
    )

    private fun stripeMoreFeaturesExpand() = BenchmarkCase(
        id = "stripe-more-features-expand",
        description = "Stripe hidden Payments rows behind 'More features' expand",
        pageSource = PageSource.Url("https://stripe.com/us/pricing"),
        query = "Is 3D Secure authentication available on Stripe's standard pricing page under Payments?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer confirms that 3D Secure authentication is available on Stripe's pricing page"),
        constraints = BenchmarkConstraints(maxIterations = 12)
    )

    private fun stripeFaqDiscounts() = BenchmarkCase(
        id = "stripe-faq-discounts",
        description = "Stripe FAQ: volume discounts (accordion)",
        pageSource = PageSource.Url("https://stripe.com/us/pricing"),
        query = "According to Stripe's FAQ, does Stripe offer discounts for large processing volumes?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions custom pricing for businesses with large processing volumes"),
        constraints = BenchmarkConstraints(maxIterations = 11)
    )

    private fun stripeCustomRoi() = BenchmarkCase(
        id = "stripe-custom-roi",
        description = "Stripe Custom pricing section Forrester ROI stat (deep scroll)",
        pageSource = PageSource.Url("https://stripe.com/us/pricing"),
        query = "What ROI does Stripe cite from the Forrester study on the custom pricing section?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions more than 3x return on investment from the Forrester study"),
        constraints = BenchmarkConstraints(maxIterations = 10)
    )

    // ==================== Notion Pricing ====================

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

    private fun notionPlusPrice() = BenchmarkCase(
        id = "notion-plus-price",
        description = "Notion Plus plan price per member (plan card near top)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "What is the price of the Notion Plus plan per member per month?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions the Plus plan costs approximately $10 per member per month"),
        constraints = BenchmarkConstraints(maxIterations = 7)
    )

    private fun notionBusinessPrice() = BenchmarkCase(
        id = "notion-business-price",
        description = "Notion Business plan price per member (recommended card)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "What is the price of the Notion Business plan per member per month?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions the Business plan costs approximately $20 per member per month"),
        constraints = BenchmarkConstraints(maxIterations = 7)
    )

    private fun notionFreeFileUpload() = BenchmarkCase(
        id = "notion-free-file-upload",
        description = "Notion Free plan file upload size limit (comparison grid)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "What is the maximum file upload size on Notion's Free plan?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions 5 MB as the maximum file upload size on the Free plan"),
        constraints = BenchmarkConstraints(maxIterations = 9)
    )

    private fun notionGuestLimitFree() = BenchmarkCase(
        id = "notion-guest-limit-free",
        description = "Notion Free plan external guest limit (comparison grid)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "How many external guests can you invite on Notion's Free plan?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions 10 external guests as the limit on the Free plan"),
        constraints = BenchmarkConstraints(maxIterations = 9)
    )

    private fun notionAutomationsPlans() = BenchmarkCase(
        id = "notion-automations-plans",
        description = "Notion automations: Free vs Plus capabilities (deep comparison grid)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "What automation capabilities does the Notion Plus plan offer compared to the Free plan?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions that Plus includes custom database automations compared to Free's basic buttons"),
        constraints = BenchmarkConstraints(maxIterations = 10)
    )

    private fun notionFaqStudentDiscount() = BenchmarkCase(
        id = "notion-faq-student-discount",
        description = "Notion FAQ: student discount details (accordion)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "According to Notion's FAQ, what discount do students get?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions that students can get the Plus Plan for free"),
        constraints = BenchmarkConstraints(maxIterations = 11)
    )

    private fun notionFaqRefundPolicy() = BenchmarkCase(
        id = "notion-faq-refund-policy",
        description = "Notion FAQ: refund policy timeframes (accordion)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "According to Notion's FAQ, within how many days of signing up can you get a refund for monthly vs annual billing?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions three days for monthly billing and 30 days for annual billing refunds"),
        constraints = BenchmarkConstraints(maxIterations = 12)
    )

    private fun notionCustomDomainPrice() = BenchmarkCase(
        id = "notion-custom-domain-price",
        description = "Notion custom domain price (deep in Web publishing section)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "How much does a custom domain cost on Notion Sites when paid annually?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions approximately $8 per month per domain when paid annually"),
        constraints = BenchmarkConstraints(maxIterations = 11)
    )

    private fun notionChartsFreeLimit() = BenchmarkCase(
        id = "notion-charts-free-limit",
        description = "Notion Free plan chart limit (deep Database features section)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "How many charts can you create on Notion's Free plan?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions 1 chart as the limit on the Free plan"),
        constraints = BenchmarkConstraints(maxIterations = 11)
    )

    private fun notionPhoneSupport() = BenchmarkCase(
        id = "notion-phone-support",
        description = "Notion phone support (does not exist)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "What is Notion's phone support number and what hours is it available?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    private fun notionVideoConferencing() = BenchmarkCase(
        id = "notion-video-conferencing",
        description = "Notion video conferencing feature (does not exist)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "Which Notion plans include built-in video conferencing with screen sharing?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    private fun notionLifetimePlan() = BenchmarkCase(
        id = "notion-lifetime-plan",
        description = "Notion lifetime/perpetual plan (does not exist)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "What is the price of Notion's lifetime plan with a one-time payment?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    private fun notionSamlSso() = BenchmarkCase(
        id = "notion-saml-sso",
        description = "Notion SAML SSO plan availability (deep comparison grid)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "Which Notion plans include SAML single sign-on (SSO)?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions that Business and Enterprise plans include SAML SSO"),
        constraints = BenchmarkConstraints(maxIterations = 10)
    )

    private fun notionPageHistory() = BenchmarkCase(
        id = "notion-page-history",
        description = "Notion Plus plan page history days (comparison grid)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "How many days of page history does the Notion Plus plan include?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions 30 days of page history for the Plus plan"),
        constraints = BenchmarkConstraints(maxIterations = 10)
    )

    private fun notionFaqPaymentFails() = BenchmarkCase(
        id = "notion-faq-payment-fails",
        description = "Notion FAQ: payment retry count (accordion)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "According to Notion's FAQ, how many times may a failed payment be retried?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions that failed payments may be retried up to 8 times"),
        constraints = BenchmarkConstraints(maxIterations = 12)
    )

    private fun notionCustomAgentsPrice() = BenchmarkCase(
        id = "notion-custom-agents-price",
        description = "Notion Custom Agents usage price (small callout, not tier headline)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "What is the price for Notion Custom Agents after the free trial?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions $10 per 1,000 credits for Notion Custom Agents"),
        constraints = BenchmarkConstraints(maxIterations = 9)
    )

    private fun notionAiDataRetention() = BenchmarkCase(
        id = "notion-ai-data-retention",
        description = "Notion AI data retention: Enterprise vs Business (comparison grid)",
        pageSource = PageSource.Url("https://www.notion.com/pricing"),
        query = "What is the Notion AI data retention policy for Enterprise vs Business?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions zero data retention for Enterprise and 30-day retention for Business"),
        constraints = BenchmarkConstraints(maxIterations = 10)
    )

    // ==================== Cloudflare Plans ====================

    fun cloudflare(): List<BenchmarkCase> = listOf(
        cfFreePrice(),
        cfBusinessPrice(),
        cfEnterpriseSales(),
        cfDevPlatformR2Storage(),
        cfDevPlatformKvReads(),
        cfWorkersOverage(),
        cfProMonthlyPrice(),
        cfVideoStreaming(),
        cfPhoneSupportPro(),
        cfEmailHosting(),
        cfDedicatedIp()
    )

    private fun cfFreePrice() = BenchmarkCase(
        id = "cf-free-price",
        description = "Cloudflare Free plan price (plan card near top)",
        pageSource = PageSource.Url("https://www.cloudflare.com/plans/"),
        query = "What does the Cloudflare Free plan cost per month?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer states the Free plan costs $0 per month or is free"),
        constraints = BenchmarkConstraints(maxIterations = 7)
    )

    private fun cfBusinessPrice() = BenchmarkCase(
        id = "cf-business-price",
        description = "Cloudflare Business plan monthly price (plan card)",
        pageSource = PageSource.Url("https://www.cloudflare.com/plans/"),
        query = "What is the Cloudflare Business plan price per month?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions the Business plan costs $200 per month"),
        constraints = BenchmarkConstraints(maxIterations = 7)
    )

    private fun cfEnterpriseSales() = BenchmarkCase(
        id = "cf-enterprise-sales",
        description = "Cloudflare Enterprise pricing approach (contact sales)",
        pageSource = PageSource.Url("https://www.cloudflare.com/plans/"),
        query = "How do you get pricing for Cloudflare's Enterprise plan?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions contacting sales for Enterprise pricing"),
        constraints = BenchmarkConstraints(maxIterations = 7)
    )

    private fun cfDevPlatformR2Storage() = BenchmarkCase(
        id = "cf-dev-platform-r2-storage",
        description = "Cloudflare R2 free-tier storage (Storage & Data tab)",
        pageSource = PageSource.Url("https://www.cloudflare.com/plans/"),
        query = "How much free storage per month does Cloudflare R2 include on the free tier?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions 10 GB of free storage per month for R2"),
        constraints = BenchmarkConstraints(maxIterations = 12)
    )

    private fun cfVideoStreaming() = BenchmarkCase(
        id = "cf-video-streaming",
        description = "Cloudflare video CDN/streaming pricing (not on plans page)",
        pageSource = PageSource.Url("https://www.cloudflare.com/plans/"),
        query = "What is the per-minute cost for Cloudflare Stream video encoding and delivery?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    private fun cfPhoneSupportPro() = BenchmarkCase(
        id = "cf-phone-support-pro",
        description = "Cloudflare phone support for Pro plan (not available)",
        pageSource = PageSource.Url("https://www.cloudflare.com/plans/"),
        query = "What is the phone support number for Cloudflare Pro plan customers?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    private fun cfEmailHosting() = BenchmarkCase(
        id = "cf-email-hosting",
        description = "Cloudflare email hosting pricing (not on plans page)",
        pageSource = PageSource.Url("https://www.cloudflare.com/plans/"),
        query = "How much does Cloudflare email hosting cost per mailbox per month?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    private fun cfDedicatedIp() = BenchmarkCase(
        id = "cf-dedicated-ip",
        description = "Cloudflare dedicated IP address pricing (does not exist)",
        pageSource = PageSource.Url("https://www.cloudflare.com/plans/"),
        query = "What is the monthly cost for a dedicated IP address on Cloudflare?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    private fun cfDevPlatformKvReads() = BenchmarkCase(
        id = "cf-dev-platform-kv-reads",
        description = "Cloudflare Workers KV free-tier reads (Storage & Data tab)",
        pageSource = PageSource.Url("https://www.cloudflare.com/plans/"),
        query = "On Cloudflare's pricing page, how many Workers KV read requests per day are included on the free tier?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions 100,000 KV read requests per day on the free tier"),
        constraints = BenchmarkConstraints(maxIterations = 12)
    )

    fun cfWorkersOverage() = BenchmarkCase(
        id = "cf-workers-overage",
        description = "Cloudflare Workers paid price per million requests (Compute tab)",
        pageSource = PageSource.Url("https://www.cloudflare.com/plans/"),
        query = "What is the Cloudflare Workers paid price per million requests?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions $0.30 per million requests for Workers"),
        constraints = BenchmarkConstraints(maxIterations = 10)
    )

    private fun cfProMonthlyPrice() = BenchmarkCase(
        id = "cf-pro-monthly-price",
        description = "Cloudflare Pro monthly (not annual) price (secondary billing text)",
        pageSource = PageSource.Url("https://www.cloudflare.com/plans/"),
        query = "What is the Cloudflare Pro plan price per month if billed monthly (not the annual rate)?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions $25 per month when billed monthly for the Pro plan"),
        constraints = BenchmarkConstraints(maxIterations = 9)
    )

    // ==================== HubSpot Pricing ====================

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

    private fun hubspotStarterPrice() = BenchmarkCase(
        id = "hubspot-starter-price",
        description = "HubSpot Marketing Hub Starter price (plan card)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "What is the starting price for HubSpot Marketing Hub Starter?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions the Starter plan starts at approximately $20 per month"),
        constraints = BenchmarkConstraints(maxIterations = 7)
    )

    private fun hubspotProfessionalPrice() = BenchmarkCase(
        id = "hubspot-professional-price",
        description = "HubSpot Marketing Hub Professional starting price (plan card)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "What is the starting price for HubSpot Marketing Hub Professional?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions the Professional plan starts at approximately $890 per month"),
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    private fun hubspotFreeToolsMention() = BenchmarkCase(
        id = "hubspot-free-tools",
        description = "HubSpot free marketing tools availability (visible on page)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "Does HubSpot offer any free marketing tools without a paid plan?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer confirms HubSpot offers free marketing tools"),
        constraints = BenchmarkConstraints(maxIterations = 9)
    )

    private fun hubspotFaqAnnualDiscount() = BenchmarkCase(
        id = "hubspot-faq-annual-discount",
        description = "HubSpot FAQ: annual commitment discount (accordion)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "According to HubSpot's FAQ, do you get a discount for committing to an annual plan?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer addresses whether an annual commitment provides a discount"),
        constraints = BenchmarkConstraints(maxIterations = 11)
    )

    private fun hubspotFaqAdditionalContacts() = BenchmarkCase(
        id = "hubspot-faq-additional-contacts",
        description = "HubSpot FAQ: additional contacts pricing (accordion)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "According to HubSpot's FAQ, can you purchase additional marketing contacts beyond what's included?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer confirms additional marketing contacts can be purchased"),
        constraints = BenchmarkConstraints(maxIterations = 11)
    )

    private fun hubspotFaqCrmIncluded() = BenchmarkCase(
        id = "hubspot-faq-crm-included",
        description = "HubSpot FAQ: CRM included with Marketing Hub (accordion)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "According to HubSpot's FAQ, is the CRM included with Marketing Hub?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer confirms the CRM is included with Marketing Hub plans"),
        constraints = BenchmarkConstraints(maxIterations = 11)
    )

    private fun hubspotPhoneSupportNumber() = BenchmarkCase(
        id = "hubspot-phone-support-number",
        description = "HubSpot direct phone support number (not on pricing page)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "What is HubSpot's direct phone support number for Marketing Hub customers?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    private fun hubspotFreeTrialDays() = BenchmarkCase(
        id = "hubspot-free-trial-days",
        description = "HubSpot Marketing Hub free trial duration in days (not specified)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "Exactly how many days is the HubSpot Marketing Hub Professional free trial?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    private fun hubspotSocialSchedulingLimit() = BenchmarkCase(
        id = "hubspot-social-scheduling-limit",
        description = "HubSpot social media scheduling post limits (not on pricing page)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "How many social media posts per month can be scheduled with HubSpot Marketing Hub Professional?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    private fun hubspotSalesforceNative() = BenchmarkCase(
        id = "hubspot-salesforce-native",
        description = "HubSpot native Salesforce connector pricing (not on this page)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "What is the price of HubSpot's native Salesforce integration add-on for Marketing Hub?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    private fun hubspotFaqEmailLimit() = BenchmarkCase(
        id = "hubspot-faq-email-limit",
        description = "HubSpot FAQ: Professional email send limit multiplier (multi-bullet accordion)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "For HubSpot Marketing Hub Professional, what is the monthly email send limit relative to the marketing contact tier?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions 10 times or 10x the marketing contact tier for Professional email send limit"),
        constraints = BenchmarkConstraints(maxIterations = 12)
    )

    private fun hubspotFaqContactsEnterprise() = BenchmarkCase(
        id = "hubspot-faq-contacts-enterprise",
        description = "HubSpot FAQ: Enterprise included marketing contacts (accordion)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "How many marketing contacts are included with HubSpot Marketing Hub Enterprise?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions 10,000 marketing contacts included with Enterprise"),
        constraints = BenchmarkConstraints(maxIterations = 12)
    )

    private fun hubspotAeoTrialPrompts() = BenchmarkCase(
        id = "hubspot-aeo-trial-prompts",
        description = "HubSpot AEO trial ChatGPT prompts (hero card fine print)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "During the HubSpot AEO trial, how many prompts are tracked in ChatGPT?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions 10 prompts tracked in ChatGPT during the AEO trial"),
        constraints = BenchmarkConstraints(maxIterations = 9)
    )

    private fun hubspotAeoIncludedTiers() = BenchmarkCase(
        id = "hubspot-aeo-included-tiers",
        description = "HubSpot AEO included tiers (footnote, not tier bullets)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "Beyond standalone purchase, which HubSpot Marketing Hub editions include AEO?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions Professional and Enterprise editions include AEO"),
        constraints = BenchmarkConstraints(maxIterations = 9)
    )

    private fun hubspotOnboardingFee() = BenchmarkCase(
        id = "hubspot-onboarding-fee",
        description = "HubSpot Professional onboarding fee (asterisk footnote)",
        pageSource = PageSource.Url("https://www.hubspot.com/pricing/marketing"),
        query = "What is the required one-time Professional Onboarding fee for HubSpot Marketing Hub Professional?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions a required one-time onboarding fee for Professional"),
        constraints = BenchmarkConstraints(maxIterations = 10)
    )

    // ==================== GitHub Pricing ====================

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

    private fun ghFreePrice() = BenchmarkCase(
        id = "gh-free-price",
        description = "GitHub Free plan price (plan card near top)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "What does the GitHub Free plan cost per month?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer states GitHub Free costs $0 per month or is free"),
        constraints = BenchmarkConstraints(maxIterations = 7)
    )

    private fun ghTeamPrice() = BenchmarkCase(
        id = "gh-team-price",
        description = "GitHub Team plan price per user (plan card)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "What is the GitHub Team plan price per user per month?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions $4 per user per month for the Team plan"),
        constraints = BenchmarkConstraints(maxIterations = 7)
    )

    private fun ghEnterprisePrice() = BenchmarkCase(
        id = "gh-enterprise-price",
        description = "GitHub Enterprise starting price per user (plan card)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "What is the starting price for GitHub Enterprise per user per month?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions $21 per user per month for Enterprise"),
        constraints = BenchmarkConstraints(maxIterations = 7)
    )

    private fun ghPackagesStorageTeam() = BenchmarkCase(
        id = "gh-packages-storage-team",
        description = "GitHub Packages storage for Team plan (plan card feature)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "How much Packages storage does the GitHub Team plan include?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions 2 GB of Packages storage for the Team plan"),
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    private fun ghSamlSsoAvailability() = BenchmarkCase(
        id = "gh-saml-sso",
        description = "GitHub SAML SSO plan availability (deep comparison table)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "Which GitHub plans include SAML single sign-on (SSO)?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions Enterprise plan includes SAML SSO"),
        constraints = BenchmarkConstraints(maxIterations = 10)
    )

    private fun ghPremiumSupportSla() = BenchmarkCase(
        id = "gh-premium-support-sla",
        description = "GitHub Premium Support SLA for urgent tickets (deep comparison)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "What is the SLA response time for urgent tickets with GitHub Premium Support?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions a 30-minute SLA response time for urgent tickets"),
        constraints = BenchmarkConstraints(maxIterations = 10)
    )

    private fun ghCodespaces4CoreHours() = BenchmarkCase(
        id = "gh-codespaces-4core-hours",
        description = "GitHub Codespaces free hours for 4-core (expandable row)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "How many free hours does GitHub Codespaces give on a 4-core machine?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions 30 hours free on a 4-core machine"),
        constraints = BenchmarkConstraints(maxIterations = 12)
    )

    private fun ghOnPremisePrice() = BenchmarkCase(
        id = "gh-on-premise-price",
        description = "GitHub Enterprise Server on-premise pricing (not on this page)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "What is the per-user price for GitHub Enterprise Server (self-hosted/on-premise)?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    private fun ghPhoneSupportTeam() = BenchmarkCase(
        id = "gh-phone-support-team",
        description = "GitHub phone support for Team plan (not available)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "What is the phone number for GitHub Team plan phone support?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    private fun ghBitbucketMigration() = BenchmarkCase(
        id = "gh-bitbucket-migration",
        description = "GitHub BitBucket migration tool pricing (does not exist on page)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "What does GitHub charge for the automated BitBucket repository migration tool?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    private fun ghAiCodeReview() = BenchmarkCase(
        id = "gh-ai-code-review",
        description = "GitHub AI code review add-on pricing (not a listed product)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "How much does GitHub's dedicated AI Code Review add-on cost per repository per month?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    private fun ghActionsMinutesTeam() = BenchmarkCase(
        id = "gh-actions-minutes-team",
        description = "GitHub Actions minutes for Team (anchor jump + deep comparison table)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "How many GitHub Actions minutes per month does the GitHub Team plan include?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions 3,000 Actions minutes per month for the Team plan"),
        constraints = BenchmarkConstraints(maxIterations = 13)
    )

    private fun ghDataResidencyCloud() = BenchmarkCase(
        id = "gh-data-residency-cloud",
        description = "GitHub Enterprise data residency cloud provider (expandable plan-card row)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "Which cloud provider does GitHub Enterprise Cloud use for data residency?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions Microsoft Azure as the cloud provider for data residency"),
        constraints = BenchmarkConstraints(maxIterations = 11)
    )

    private fun ghCodespacesFreeHours() = BenchmarkCase(
        id = "gh-codespaces-free-hours",
        description = "GitHub Codespaces free hours for 2-core (expandable compare-table row)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "How many free hours does GitHub Codespaces give on a 2-core machine?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions 60 hours free on a 2-core machine"),
        constraints = BenchmarkConstraints(maxIterations = 12)
    )

    private fun ghCopilotFreeLimits() = BenchmarkCase(
        id = "gh-copilot-free-limits",
        description = "GitHub Copilot free tier limits (add-on section, not main grid)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "How many free completions and chat requests per month does GitHub Copilot offer?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions up to 2,000 completions and 50 chat requests per month on the free tier"),
        constraints = BenchmarkConstraints(maxIterations = 10)
    )

    private fun ghLfsPrice() = BenchmarkCase(
        id = "gh-lfs-price",
        description = "GitHub Git LFS monthly price (add-on section)",
        pageSource = PageSource.Url("https://github.com/pricing"),
        query = "How much does Git Large File Storage cost per month on GitHub?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions $5 per month for Git LFS with 50 GB bandwidth and 50 GB storage"),
        constraints = BenchmarkConstraints(maxIterations = 10)
    )

    // ==================== Intercom Pricing ====================

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

    private fun intercomEssentialSeatPrice() = BenchmarkCase(
        id = "intercom-essential-seat",
        description = "Intercom Essential plan seat price (plan card near top)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "What is the Intercom Essential plan price per seat per month?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions $29 per seat per month for the Essential plan"),
        constraints = BenchmarkConstraints(maxIterations = 7)
    )

    private fun intercomAdvancedSeatPrice() = BenchmarkCase(
        id = "intercom-advanced-seat",
        description = "Intercom Advanced plan seat price (plan card)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "What is the Intercom Advanced plan price per seat per month?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions $85 per seat per month for the Advanced plan"),
        constraints = BenchmarkConstraints(maxIterations = 7)
    )

    private fun intercomExpertSeatPrice() = BenchmarkCase(
        id = "intercom-expert-seat",
        description = "Intercom Expert plan seat price (plan card)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "What is the Intercom Expert plan price per seat per month?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions $132 per seat per month for the Expert plan"),
        constraints = BenchmarkConstraints(maxIterations = 7)
    )

    private fun intercomFinOutcomePrice() = BenchmarkCase(
        id = "intercom-fin-outcome-price",
        description = "Intercom Fin AI Agent per-outcome price (all plan cards)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "How much does Intercom charge per Fin AI Agent outcome?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions $0.99 per Fin AI Agent outcome"),
        constraints = BenchmarkConstraints(maxIterations = 7)
    )

    private fun intercomProAddonPrice() = BenchmarkCase(
        id = "intercom-pro-addon",
        description = "Intercom Pro add-on price and included conversations (Add-ons section)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "What is the price of Intercom's Pro add-on and how many conversations does it include?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions the Pro add-on costs $99/mo and includes 1,000 conversations"),
        constraints = BenchmarkConstraints(maxIterations = 9)
    )

    private fun intercomCopilotAddonPrice() = BenchmarkCase(
        id = "intercom-copilot-addon",
        description = "Intercom Copilot add-on price per agent (Add-ons section)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "What is the per-agent monthly price of the Intercom Copilot add-on?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions $29 per agent per month for the Copilot add-on"),
        constraints = BenchmarkConstraints(maxIterations = 9)
    )

    private fun intercomProactiveSupportPlus() = BenchmarkCase(
        id = "intercom-proactive-support-plus",
        description = "Intercom Proactive Support Plus price and included messages (Add-ons)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "What is Intercom's Proactive Support Plus add-on price and how many messages does it include?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions $99/mo and includes 500 messages per month"),
        constraints = BenchmarkConstraints(maxIterations = 9)
    )

    private fun intercomFaqPricingComponents() = BenchmarkCase(
        id = "intercom-faq-pricing-components",
        description = "Intercom FAQ: pricing components seats + usage (accordion)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "According to Intercom's FAQ, what are the two main components of Intercom pricing?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions seats and usage as the two main pricing components"),
        constraints = BenchmarkConstraints(maxIterations = 11)
    )

    private fun intercomAdvancedLiteSeats() = BenchmarkCase(
        id = "intercom-advanced-lite-seats",
        description = "Intercom Advanced plan included free Lite seats (plan feature bullet)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "How many free Lite seats are included with the Intercom Advanced plan?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions 20 free Lite seats included with the Advanced plan"),
        constraints = BenchmarkConstraints(maxIterations = 9)
    )

    private fun intercomExpertSlaFeature() = BenchmarkCase(
        id = "intercom-expert-sla",
        description = "Intercom Expert plan SLA feature (deep plan feature list)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "Which Intercom plan includes Service Level Agreements (SLAs)?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions the Expert plan includes SLAs"),
        constraints = BenchmarkConstraints(maxIterations = 9)
    )

    private fun intercomExpertHipaa() = BenchmarkCase(
        id = "intercom-expert-hipaa",
        description = "Intercom HIPAA support plan availability (Expert only)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "Which Intercom plan includes HIPAA compliance support?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions the Expert plan includes HIPAA compliance support"),
        constraints = BenchmarkConstraints(maxIterations = 9)
    )

    private fun intercomFaqMinimumStart() = BenchmarkCase(
        id = "intercom-faq-minimum-start",
        description = "Intercom FAQ: minimum to get started (accordion)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "According to Intercom's FAQ, what is the minimum required to get started with Intercom?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions at least one seat is required to get started"),
        constraints = BenchmarkConstraints(maxIterations = 11)
    )

    private fun intercomPhoneSupportNumber() = BenchmarkCase(
        id = "intercom-phone-support-number",
        description = "Intercom direct phone support number (not on pricing page)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "What is Intercom's direct phone support number for customers?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    private fun intercomApiRateLimits() = BenchmarkCase(
        id = "intercom-api-rate-limits",
        description = "Intercom API rate limits (not on pricing page)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "What are the API rate limits per plan on Intercom's pricing page?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    private fun intercomFreePlan() = BenchmarkCase(
        id = "intercom-free-plan",
        description = "Intercom free plan/tier (does not exist)",
        pageSource = PageSource.Url("https://www.intercom.com/pricing"),
        query = "What features are included in Intercom's free plan with no payment required?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        constraints = BenchmarkConstraints(maxIterations = 8)
    )
}
