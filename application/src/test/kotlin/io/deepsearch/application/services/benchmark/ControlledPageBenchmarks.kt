package io.deepsearch.application.services.benchmark

import io.deepsearch.domain.agents.NavigationAction

/**
 * Benchmark case definitions for controlled HTML pages.
 *
 * Each case defines:
 * - The HTML content and the query
 * - The expected answer (or that the agent should give up)
 * - The ideal action sequence a perfect agent would take
 * - Constraints that flag clear inefficiencies
 *
 * The ideal sequences represent the most efficient path through each page.
 * Because the VLM is non-deterministic, the scoring uses LCS-based comparison
 * rather than requiring an exact match.
 */
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

    /**
     * Answer is directly visible on the page. No interaction needed.
     * Optimal: just read the viewport and answer immediately.
     */
    fun visibleContent() = BenchmarkCase(
        id = "visible-content",
        description = "Answer visible without any interaction",
        pageSource = PageSource.InlineHtml(ControlledPageHtml.VISIBLE_ANSWER_PAGE_HTML, "/visible"),
        query = "What is the company motto?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("INNOVATION-DRIVES-PROGRESS")),
        idealActionSequence = listOf(
            NavigationAction.AnswerFound::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 3,
            maxClickCount = 0,
            maxScrollCount = 0
        )
    )

    /**
     * Answer hidden behind a single FAQ accordion click.
     * Optimal: use find_on_page to locate "refund", click the accordion, read answer.
     */
    fun accordion() = BenchmarkCase(
        id = "accordion",
        description = "Answer hidden behind FAQ accordion click",
        pageSource = PageSource.InlineHtml(ControlledPageHtml.ACCORDION_PAGE_HTML, "/accordion"),
        query = "What is the refund policy code?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("REFUND-GAMMA-77")),
        idealActionSequence = listOf(
            NavigationAction.Click::class,
            NavigationAction.AnswerFound::class
        ),
        optimalIterations = 2,
        constraints = BenchmarkConstraints(
            maxIterations = 5,
            maxScrollCount = 1
        )
    )

    /**
     * Answer in a non-active tab panel. Must click the "Enterprise" tab.
     * Optimal: click Enterprise tab, read the price.
     */
    fun tabs() = BenchmarkCase(
        id = "tabs",
        description = "Answer in non-active tab panel",
        pageSource = PageSource.InlineHtml(ControlledPageHtml.TAB_PAGE_HTML, "/tabs"),
        query = "What is the enterprise plan price?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("499")),
        idealActionSequence = listOf(
            NavigationAction.Click::class,
            NavigationAction.AnswerFound::class
        ),
        optimalIterations = 2,
        constraints = BenchmarkConstraints(
            maxIterations = 5,
            maxScrollCount = 1
        )
    )

    /**
     * Answer behind two nested accordion levels. Must click the outer section
     * then the inner subsection.
     * Optimal: click "Authentication & API Keys" section -> click "What is the secret API key?" -> answer.
     */
    fun deepAccordion() = BenchmarkCase(
        id = "deep-accordion",
        description = "Answer behind two nested accordion levels",
        pageSource = PageSource.InlineHtml(ControlledPageHtml.DEEP_ACCORDION_PAGE_HTML, "/deep-accordion"),
        query = "What is the secret API key?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("SK-DEEP-NESTED-42X")),
        idealActionSequence = listOf(
            NavigationAction.Click::class,
            NavigationAction.Click::class,
            NavigationAction.AnswerFound::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 7,
            maxScrollCount = 2
        )
    )

    /**
     * Information does not exist on the page. Agent must explore and give up gracefully.
     * Optimal: use find_on_page to confirm absence, then give_up.
     */
    fun nonExistentInfo() = BenchmarkCase(
        id = "no-match",
        description = "Query for info that does not exist on page",
        pageSource = PageSource.InlineHtml(ControlledPageHtml.NONEXISTENT_INFO_PAGE_HTML, "/no-match"),
        query = "What is the CEO's birthday?",
        expectedOutcome = ExpectedOutcome.ShouldGiveUp,
        idealActionSequence = listOf(
            NavigationAction.FindOnPage::class,
            NavigationAction.GiveUp::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 12
        )
    )

    /**
     * Number-dense product catalog page. Many numbers (prices, IDs, stock counts)
     * compete with element labels. Answer behind a "Show Details" button.
     * Optimal: click the show-details button, answer.
     */
    fun numberDense() = BenchmarkCase(
        id = "number-dense",
        description = "Number-dense page; avoid label hallucination",
        pageSource = PageSource.InlineHtml(ControlledPageHtml.NUMBER_DENSE_PAGE_HTML, "/number-dense"),
        query = "What is the warranty period for the ProMax model?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("36")),
        idealActionSequence = listOf(
            NavigationAction.Click::class,
            NavigationAction.AnswerFound::class
        ),
        optimalIterations = 2,
        constraints = BenchmarkConstraints(
            maxIterations = 6
        )
    )

    /**
     * 20+ interactive elements with overlapping page numbers. Answer behind
     * a specific FAQ accordion among many distractors.
     * Optimal: find the correct FAQ, click it, answer.
     */
    fun crowdedLabels() = BenchmarkCase(
        id = "crowded-labels",
        description = "Crowded labels with overlapping page numbers",
        pageSource = PageSource.InlineHtml(ControlledPageHtml.CROWDED_LABELS_PAGE_HTML, "/crowded-labels"),
        query = "What is the cancellation fee for the Premium plan?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("75")),
        idealActionSequence = listOf(
            NavigationAction.Click::class,
            NavigationAction.AnswerFound::class
        ),
        optimalIterations = 2,
        constraints = BenchmarkConstraints(
            maxIterations = 6
        )
    )

    /**
     * Navigation-heavy page with 25+ off-page links competing with FAQ accordions.
     * Optimal: identify the correct FAQ among many nav links, click it, answer.
     */
    fun navHeavy() = BenchmarkCase(
        id = "nav-heavy",
        description = "Many off-page links; find answer in FAQ accordion",
        pageSource = PageSource.InlineHtml(ControlledPageHtml.NAVIGATION_HEAVY_PAGE_HTML, "/nav-heavy"),
        query = "What is the weather cancellation policy code?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("WX-CANCEL-88R")),
        idealActionSequence = listOf(
            NavigationAction.Click::class,
            NavigationAction.AnswerFound::class
        ),
        optimalIterations = 2,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
    )

    /**
     * Prominent CTA links navigate off-page. Answer is in a small FAQ below.
     * Agent must avoid getting stuck re-clicking the CTA.
     * Optimal: scroll/find FAQ, click it, answer.
     */
    fun offPageLoop() = BenchmarkCase(
        id = "offpage-loop",
        description = "Avoid re-clicking off-page CTA; find answer in FAQ",
        pageSource = PageSource.InlineHtml(ControlledPageHtml.OFFPAGE_LOOP_PAGE_HTML, "/offpage-loop"),
        query = "What is the maximum file size for sync?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("50")),
        idealActionSequence = listOf(
            NavigationAction.ScrollToText::class,
            NavigationAction.Click::class,
            NavigationAction.AnswerFound::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 8
        )
    )

    /**
     * OneTrust cookie banner covers content. The programmatic dismissal should
     * handle it, so zero clicks needed. Content is directly visible after removal.
     */
    fun cookieOneTrust() = BenchmarkCase(
        id = "cookie-onetrust",
        description = "OneTrust cookie banner auto-dismissed",
        pageSource = PageSource.InlineHtml(ControlledPageHtml.COOKIE_ONETRUST_PAGE_HTML, "/cookie-onetrust"),
        query = "What is the activation code?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("ONETRUST-PASS-99")),
        idealActionSequence = listOf(
            NavigationAction.AnswerFound::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 3,
            maxClickCount = 0
        )
    )

    /**
     * Cookiebot cookie dialog. Same as OneTrust -- programmatic dismissal.
     */
    fun cookieCookiebot() = BenchmarkCase(
        id = "cookie-cookiebot",
        description = "Cookiebot cookie banner auto-dismissed",
        pageSource = PageSource.InlineHtml(ControlledPageHtml.COOKIE_COOKIEBOT_PAGE_HTML, "/cookie-cookiebot"),
        query = "What is the service activation key?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("COOKIEBOT-PASS-55")),
        idealActionSequence = listOf(
            NavigationAction.AnswerFound::class
        ),
        optimalIterations = 1,
        constraints = BenchmarkConstraints(
            maxIterations = 3,
            maxClickCount = 0
        )
    )

    /**
     * Custom/unknown cookie banner. Programmatic dismissal won't work, so the VLM
     * must click through it. One extra iteration to dismiss + answer.
     */
    fun cookieCustom() = BenchmarkCase(
        id = "cookie-custom",
        description = "Custom cookie banner; VLM must click to dismiss",
        pageSource = PageSource.InlineHtml(ControlledPageHtml.COOKIE_CUSTOM_PAGE_HTML, "/cookie-custom"),
        query = "What is today's access code?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("CUSTOM-BANNER-77")),
        idealActionSequence = listOf(
            NavigationAction.Click::class,
            NavigationAction.AnswerFound::class
        ),
        optimalIterations = 2,
        constraints = BenchmarkConstraints(
            maxIterations = 5
        )
    )

    /**
     * Long page (~3500px) with answer buried at the bottom. The agent should use
     * scroll_to_text to jump directly rather than scrolling 5+ times.
     * Optimal: scroll_to_text("maintenance code") -> answer_found.
     */
    fun longPageBottom() = BenchmarkCase(
        id = "long-page-bottom",
        description = "Long page; answer at bottom via scroll_to_text",
        pageSource = PageSource.InlineHtml(ControlledPageHtml.LONG_PAGE_BOTTOM_HTML, "/long-page-bottom"),
        query = "What is the system maintenance code?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("MAINT-PHOENIX-2024-X9")),
        idealActionSequence = listOf(
            NavigationAction.ScrollToText::class,
            NavigationAction.AnswerFound::class
        ),
        optimalIterations = 2,
        constraints = BenchmarkConstraints(
            maxIterations = 6,
            requiredActionTypes = setOf(
                NavigationAction.ScrollToText::class,
                NavigationAction.AnswerFound::class
            ),
            maxScrollCount = 2
        )
    )

    /**
     * Long page with FAQ accordion at the bottom. Must navigate to the FAQ area
     * and then click to expand the answer.
     * Optimal: scroll_to_text("emergency shutdown") -> click accordion -> answer_found.
     */
    fun longPageAccordionBottom() = BenchmarkCase(
        id = "long-page-accordion-bottom",
        description = "Long page; accordion FAQ at bottom",
        pageSource = PageSource.InlineHtml(
            ControlledPageHtml.LONG_PAGE_ACCORDION_BOTTOM_HTML,
            "/long-page-accordion-bottom"
        ),
        query = "What is the emergency shutdown procedure?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("ESHUT-DELTA-7X")),
        idealActionSequence = listOf(
            NavigationAction.ScrollToText::class,
            NavigationAction.Click::class,
            NavigationAction.AnswerFound::class
        ),
        optimalIterations = 3,
        constraints = BenchmarkConstraints(
            maxIterations = 8,
            maxScrollCount = 3
        )
    )

    /**
     * Large table with 25 rows. The agent should use scroll_to_text to jump
     * to "platinum" rather than scrolling through the whole table.
     * Optimal: scroll_to_text("platinum") -> answer_found.
     */
    fun searchableTable() = BenchmarkCase(
        id = "searchable-table",
        description = "Large table; find specific row via scroll_to_text",
        pageSource = PageSource.InlineHtml(ControlledPageHtml.SEARCHABLE_TABLE_HTML, "/searchable-table"),
        query = "What is the processing time for platinum-tier orders?",
        expectedOutcome = ExpectedOutcome.AnswerContains(listOf("2 hour")),
        idealActionSequence = listOf(
            NavigationAction.ScrollToText::class,
            NavigationAction.AnswerFound::class
        ),
        optimalIterations = 2,
        constraints = BenchmarkConstraints(
            maxIterations = 5,
            maxScrollCount = 2
        )
    )
}
