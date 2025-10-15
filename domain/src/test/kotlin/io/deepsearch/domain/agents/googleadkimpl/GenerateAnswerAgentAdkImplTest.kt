package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.agents.GenerateAnswerInput
import io.deepsearch.domain.agents.IGenerateAnswerAgent
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.SearchQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertTrue

class GenerateAnswerAgentAdkImplTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IGenerateAnswerAgent>()

    @Test
    fun `generate answer from markdown content`() = runTest(testCoroutineDispatcher) {
        val query = "What is Example Domain used for?"

        val markdown = """
            # Example Domain
            
            This domain is for use in illustrative examples in documents. 
            You may use this domain in literature without prior coordination or asking for permission.
            
            ## Purpose
            
            Example domains are reserved for documentation and educational purposes. 
            They provide a safe, non-commercial space for demonstrating concepts.
            
            ## More Information
            
            [More information...](https://www.iana.org/domains/example)
        """.trimIndent()

        val output = agent.generate(GenerateAnswerInput(query, markdown))

        assertTrue(output.answer.isNotBlank(), "Generated answer should not be blank")
    }

    @Test
    fun `generate answer when content is not relevant to query`() = runTest(testCoroutineDispatcher) {
        val query = "What are the ingredients for chocolate chip cookies?"

        val markdown = """
            # Example Domain
            
            This domain is for use in illustrative examples in documents. 
            You may use this domain in literature without prior coordination or asking for permission.
            
            ## Purpose
            
            Example domains are reserved for documentation and educational purposes. 
            They provide a safe, non-commercial space for demonstrating concepts.
            
            ## More Information
            
            [More information...](https://www.iana.org/domains/example)
        """.trimIndent()

        val output = agent.generate(GenerateAnswerInput(query, markdown))

        assertTrue(output.answer.isNotBlank(), "Generated answer should not be blank even when content is not relevant")
    }
}


