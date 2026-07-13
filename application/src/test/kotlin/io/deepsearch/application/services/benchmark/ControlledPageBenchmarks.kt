package io.deepsearch.application.services.benchmark

object ControlledPageBenchmarks {

    fun all(): List<BenchmarkCase> = listOf(
        visibleContent(),
        accordion(),
        tabs(),
        deepAccordion(),
        nonExistentInfo(),
        numberDense(),
        crowdedLabels(),
        navHeavy(),
        offPageLoop(),
        cookieOneTrust(),
        cookieCookiebot(),
        cookieCustom(),
        longPageBottom(),
        longPageAccordionBottom(),
        searchableTable()
    )

    fun visibleContent() = BenchmarkCase(
        id = "visible-content",
        description = "Answer visible without any interaction",
        pageSource = PageSource.InlineHtml(ControlledPageHtml.VISIBLE_ANSWER_PAGE_HTML, "/visible"),
        query = "What is the company motto?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer contains the motto 'INNOVATION-DRIVES-PROGRESS'"),
        constraints = BenchmarkConstraints(maxIterations = 3)
    )

    fun accordion() = BenchmarkCase(
        id = "accordion",
        description = "Answer hidden behind FAQ accordion click",
        pageSource = PageSource.InlineHtml(ControlledPageHtml.ACCORDION_PAGE_HTML, "/accordion"),
        query = "What is the refund policy code?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer contains the refund policy code 'REFUND-GAMMA-77'"),
        constraints = BenchmarkConstraints(maxIterations = 5)
    )

    fun tabs() = BenchmarkCase(
        id = "tabs",
        description = "Answer in non-active tab panel",
        pageSource = PageSource.InlineHtml(ControlledPageHtml.TAB_PAGE_HTML, "/tabs"),
        query = "What is the enterprise plan price?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions the enterprise plan price of $499 or 499"),
        constraints = BenchmarkConstraints(maxIterations = 5)
    )

    fun deepAccordion() = BenchmarkCase(
        id = "deep-accordion",
        description = "Answer behind two nested accordion levels",
        pageSource = PageSource.InlineHtml(ControlledPageHtml.DEEP_ACCORDION_PAGE_HTML, "/deep-accordion"),
        query = "What is the secret API key?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer contains the secret API key 'SK-DEEP-NESTED-42X'"),
        constraints = BenchmarkConstraints(maxIterations = 7)
    )

    fun nonExistentInfo() = BenchmarkCase(
        id = "no-match",
        description = "Query for info that does not exist on page",
        pageSource = PageSource.InlineHtml(ControlledPageHtml.NONEXISTENT_INFO_PAGE_HTML, "/no-match"),
        query = "What is the CEO's birthday?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp
    )

    fun numberDense() = BenchmarkCase(
        id = "number-dense",
        description = "Number-dense page; avoid label hallucination",
        pageSource = PageSource.InlineHtml(ControlledPageHtml.NUMBER_DENSE_PAGE_HTML, "/number-dense"),
        query = "What is the warranty period for the ProMax model?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions 36 months as the warranty period for the ProMax model"),
        constraints = BenchmarkConstraints(maxIterations = 6)
    )

    fun crowdedLabels() = BenchmarkCase(
        id = "crowded-labels",
        description = "Crowded labels with overlapping page numbers",
        pageSource = PageSource.InlineHtml(ControlledPageHtml.CROWDED_LABELS_PAGE_HTML, "/crowded-labels"),
        query = "What is the cancellation fee for the Premium plan?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions $75 or 75 as the cancellation fee for the Premium plan"),
        constraints = BenchmarkConstraints(maxIterations = 6)
    )

    fun navHeavy() = BenchmarkCase(
        id = "nav-heavy",
        description = "Many off-page links; find answer in FAQ accordion",
        pageSource = PageSource.InlineHtml(ControlledPageHtml.NAVIGATION_HEAVY_PAGE_HTML, "/nav-heavy"),
        query = "What is the weather cancellation policy code?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer contains the weather cancellation policy code 'WX-CANCEL-88R'"),
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    fun offPageLoop() = BenchmarkCase(
        id = "offpage-loop",
        description = "Avoid re-clicking off-page CTA; find answer in FAQ",
        pageSource = PageSource.InlineHtml(ControlledPageHtml.OFFPAGE_LOOP_PAGE_HTML, "/offpage-loop"),
        query = "What is the maximum file size for sync?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions 50 MB or 50MB as the maximum file size for sync"),
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    fun cookieOneTrust() = BenchmarkCase(
        id = "cookie-onetrust",
        description = "OneTrust cookie banner auto-dismissed",
        pageSource = PageSource.InlineHtml(ControlledPageHtml.COOKIE_ONETRUST_PAGE_HTML, "/cookie-onetrust"),
        query = "What is the activation code?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer contains the activation code 'ONETRUST-PASS-99'"),
        constraints = BenchmarkConstraints(maxIterations = 3)
    )

    fun cookieCookiebot() = BenchmarkCase(
        id = "cookie-cookiebot",
        description = "Cookiebot cookie banner auto-dismissed",
        pageSource = PageSource.InlineHtml(ControlledPageHtml.COOKIE_COOKIEBOT_PAGE_HTML, "/cookie-cookiebot"),
        query = "What is the service activation key?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer contains the service activation key 'COOKIEBOT-PASS-55'"),
        constraints = BenchmarkConstraints(maxIterations = 3)
    )

    fun cookieCustom() = BenchmarkCase(
        id = "cookie-custom",
        description = "Custom cookie banner; VLM must click to dismiss",
        pageSource = PageSource.InlineHtml(ControlledPageHtml.COOKIE_CUSTOM_PAGE_HTML, "/cookie-custom"),
        query = "What is today's access code?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer contains the access code 'CUSTOM-BANNER-77'"),
        constraints = BenchmarkConstraints(maxIterations = 5)
    )

    fun longPageBottom() = BenchmarkCase(
        id = "long-page-bottom",
        description = "Long page; answer at bottom visible in full-page screenshot",
        pageSource = PageSource.InlineHtml(ControlledPageHtml.LONG_PAGE_BOTTOM_HTML, "/long-page-bottom"),
        query = "What is the system maintenance code?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer contains the system maintenance code 'MAINT-PHOENIX-2024-X9'"),
        constraints = BenchmarkConstraints(maxIterations = 6)
    )

    fun longPageAccordionBottom() = BenchmarkCase(
        id = "long-page-accordion-bottom",
        description = "Long page; accordion FAQ at bottom",
        pageSource = PageSource.InlineHtml(
            ControlledPageHtml.LONG_PAGE_ACCORDION_BOTTOM_HTML,
            "/long-page-accordion-bottom"
        ),
        query = "What is the emergency shutdown procedure?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer contains the emergency shutdown procedure code 'ESHUT-DELTA-7X'"),
        constraints = BenchmarkConstraints(maxIterations = 8)
    )

    fun searchableTable() = BenchmarkCase(
        id = "searchable-table",
        description = "Large table; find specific row in full-page view",
        pageSource = PageSource.InlineHtml(ControlledPageHtml.SEARCHABLE_TABLE_HTML, "/searchable-table"),
        query = "What is the processing time for platinum-tier orders?",
        expectedOutcome = ExpectedOutcome.AnswerSatisfies("The answer mentions 2 hours as the processing time for platinum-tier orders"),
        constraints = BenchmarkConstraints(maxIterations = 5)
    )
}
