package agents

import com.google.adk.agents.BaseAgent
import com.google.adk.agents.InvocationContext
import com.google.adk.agents.LlmAgent
import com.google.adk.events.Event
import com.google.genai.types.GenerateContentConfig
import io.deepsearch.domain.agents.ModelIds
import io.deepsearch.domain.agents.SearchOrchestratorAgent
import io.reactivex.rxjava3.core.Flowable

private val queryExpansionSubAgent =
    LlmAgent.builder().run {
        name("queryExpansionSubAgent")
        description("Expand a user query into more specific subqueries")
        model(ModelIds.GEMINI_2_0_FLASH_LITE.modelId)
        includeContents(LlmAgent.IncludeContents.NONE)
        generateContentConfig(
            GenerateContentConfig.builder()
                .temperature(0.2F)
                .build()
        )
        instruction(
            """
            You are the Query Expansion sub-agent. Your job is to transform the user's high-level query into a structured list of smaller, specific, and measurable subqueries.

            Output requirements:
            - Produce at most 3 subqueries that are unique, atomic, unambiguous, and ready to execute in a search engine.
            - Order subqueries by importance, starting with the most important query.
            - Keep the number of subqueries as few as possible
            - If the user request is overly broad or practically unbounded, return subqueries targeting an overview or summary of the relevant results.

            Scope and style:
            - Avoid duplicates and near-duplicates; each subquery must have a distinct purpose.
            - Prefer specificity: include entities, time ranges, versions, filetypes, and other qualifiers when helpful.
            - Keep subqueries independent so they can be executed in parallel.

            Example A:
            User query: "Find leadership info and headcount for the company"
            Expected shape:
            {
              "subqueries": [
                {
                  "query": "leadership team",
                  "rationale": "Locate official leadership page on the target site."
                },
                {
                  "query": "company size/headcount",
                  "rationale": "Search for an official mention of employee count."
                }
              ]
            }

            Example B (overly broad request):
            User query: "Show me all products on your ecommerce website"
            Expected shape:
            {
              "subqueries": [
                {
                  "query": "how many different types of products are there?",
                  "rationale": "Provide an overview of available products"
                }
              ]
            }
            """.trimIndent()
        )
        build()
    }

class QueryExpansionAgent : BaseAgent(
    SearchOrchestratorAgent::class.simpleName,
    "Expand a user query into more specific subqueries",
    listOf(queryExpansionSubAgent),
    null,
    null
) {


    override fun runAsyncImpl(invocationContext: InvocationContext): Flowable<Event> {
        return queryExpansionSubAgent.runAsync(invocationContext)
            .map { event ->
                event
            }
    }

    override fun runLiveImpl(invocationContext: InvocationContext): Flowable<Event> {
        TODO("Not yet implemented")
    }

}