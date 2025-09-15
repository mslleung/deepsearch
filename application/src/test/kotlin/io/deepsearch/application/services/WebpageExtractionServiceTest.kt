package io.deepsearch.application.services

import io.deepsearch.domain.agents.IIconInterpreterAgent
import io.deepsearch.domain.agents.IconInterpreterInput
import io.deepsearch.domain.agents.IconInterpreterOutput
import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.ITableInterpretationAgent
import io.deepsearch.domain.agents.TableIdentification
import io.deepsearch.domain.agents.TableIdentificationInput
import io.deepsearch.domain.agents.TableIdentificationOutput
import io.deepsearch.domain.browser.playwright.PlaywrightBrowser
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.agents.TableInterpretationOutput
import io.deepsearch.domain.repositories.IWebpageIconRepository
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

@EnabledIfEnvironmentVariable(named = "PLAYWRIGHT_ENABLED", matches = ".+")
class WebpageExtractionServiceTest {

    private class NoopTableAgent : ITableIdentificationAgent {
        override suspend fun generate(input: TableIdentificationInput): TableIdentificationOutput {
            // Provide a minimal hint to remove the comparison table by tokens if present
            return TableIdentificationOutput(
                tables = listOf(
                    TableIdentification(
                        xpath = "//*[contains(., 'Standard') and contains(., 'Comprehensive') and contains(., 'Ultra')]",
                        auxiliaryInfo = "comparison"
                    )
                )
            )
        }
    }

    private class StubTableInterpretationAgent : ITableInterpretationAgent {
        override suspend fun generate(input: TableInterpretationInput): TableInterpretationOutput {
            return TableInterpretationOutput(markdown = "| A | B |\n|---|---|\n| 1 | 2 |")
        }
    }

    private class EchoIconAgent : IIconInterpreterAgent {
        override suspend fun generate(input: IconInterpreterInput): IconInterpreterOutput {
            return IconInterpreterOutput(label = "menu icon")
        }
    }

    private class InMemoryIconRepo : IWebpageIconRepository {
        private val map = mutableMapOf<String, String?>()
        override suspend fun upsert(icon: io.deepsearch.domain.models.entities.WebpageIcon) {
            map[Base64.getEncoder().encodeToString(icon.imageBytesHash)] = icon.label
        }
        override suspend fun findByHash(imageBytesHash: ByteArray): io.deepsearch.domain.models.entities.WebpageIcon? {
            val key = Base64.getEncoder().encodeToString(imageBytesHash)
            val label = map[key]
            return if (map.containsKey(key)) io.deepsearch.domain.models.entities.WebpageIcon(imageBytesHash, label) else null
        }
    }

    @Test
    fun `extract webpage text for OTandP body check page`() = runTest {
        val tableAgent = NoopTableAgent()
        val iconAgent = EchoIconAgent()
        val repo = InMemoryIconRepo()
        val service = WebpageExtractionService(tableAgent, iconAgent, repo, StubTableInterpretationAgent())

        val browser = PlaywrightBrowser()
        val context = browser.createContext()
        val page = context.newPage()

        page.navigate("https://www.otandp.com/body-check/")
        val text = service.extractWebpage(page)

        assertTrue(text.contains("Body Check"))
        assertTrue(text.length > 200)

        browser.close()
    }
}


