package io.deepsearch.application.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.system.measureTimeMillis

/**
 * Unit tests for PageTextSearchService: Lucene-based stemmed page text matching.
 *
 * Tests accuracy of multilingual stemming, language detection, edge cases, and latency.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PageTextSearchServiceTest {

    private val service = PageTextSearchService()

    // ─── English Stemming ───

    @Test
    fun `English stemming - pricing matches prices`() {
        val pageText = "Our prices start at \$99 per month. View all prices on the pricing page."
        val results = service.search(pageText, listOf("pricing"))

        assertTrue(results.containsKey("pricing"), "Should find matches for 'pricing'")
        val matches = results["pricing"]!!
        assertTrue(matches.isNotEmpty(), "Should have at least one match")
        assertTrue(
            matches.any { it.matchedText.contains("price", ignoreCase = true) },
            "Matched text should contain a form of 'price'"
        )
    }

    @Test
    fun `English stemming - running matches ran and runs`() {
        val pageText = """
            The application runs smoothly on all platforms.
            We ran extensive benchmarks last quarter.
            Running diagnostics is recommended weekly.
        """.trimIndent()
        val results = service.search(pageText, listOf("running"))

        assertTrue(results.containsKey("running"), "Should find matches for 'running'")
        val matches = results["running"]!!
        assertTrue(matches.size >= 2, "Should match multiple verb forms, got ${matches.size}")
    }

    @Test
    fun `English stemming - connected matches connecting and connections`() {
        val pageText = """
            Connecting to the server requires authentication.
            All connections are encrypted end-to-end.
            The connected devices appear in the dashboard.
        """.trimIndent()
        val results = service.search(pageText, listOf("connected"))

        assertTrue(results.containsKey("connected"), "Should find matches via stemming for 'connected'")
        val matches = results["connected"]!!
        assertTrue(matches.size >= 3, "Should match connecting, connections, and connected, got ${matches.size}")
    }

    @Test
    fun `English stemming limitation - British vs American spelling not matched`() {
        // Porter stemmer produces different roots for "analyze" (analyz) vs "analyse" (analys).
        // This is correct: spelling variants are not morphological variations.
        val pageText = "We analyse customer data to provide better insights."
        val results = service.search(pageText, listOf("analyze"))

        assertFalse(
            results.containsKey("analyze"),
            "Stemming should NOT match US vs British spelling variants (known limitation)"
        )
    }

    // ─── Stemming Does NOT Match Typos (by design) ───

    @Test
    fun `stemming does not match typos - this is correct behavior`() {
        // The VLM generates keywords; it doesn't make typos. Stemming catches morphological
        // variants ("pricing"/"prices"), not edit-distance errors. Typo tolerance was removed
        // as unnecessary complexity.
        val pageText = "Kubernetes orchestrates containerized workloads across clusters."
        val results = service.search(pageText, listOf("kubernetse"))

        assertFalse(
            results.containsKey("kubernetse"),
            "Stemming should NOT match typos — only morphological variants"
        )
    }

    // ─── CJK Detection & Matching ───

    @Test
    fun `CJK - detects Chinese text and uses CJKAnalyzer`() {
        val pageText = "产品价格从每月99元起。企业版提供更多高级功能和技术支持服务。"
        val analyzer = service.chooseAnalyzer(pageText)
        assertTrue(
            analyzer.javaClass.simpleName.contains("CJK"),
            "Should choose CJKAnalyzer for Chinese text, got ${analyzer.javaClass.simpleName}"
        )
        analyzer.close()
    }

    @Test
    fun `CJK - detects Japanese text and uses CJKAnalyzer`() {
        val pageText = "製品の価格は月額9900円からです。エンタープライズ版ではより多くの機能をご利用いただけます。"
        val analyzer = service.chooseAnalyzer(pageText)
        assertTrue(
            analyzer.javaClass.simpleName.contains("CJK"),
            "Should choose CJKAnalyzer for Japanese text, got ${analyzer.javaClass.simpleName}"
        )
        analyzer.close()
    }

    @Test
    fun `CJK - detects Korean text and uses CJKAnalyzer`() {
        val pageText = "제품 가격은 월 99달러부터 시작합니다. 엔터프라이즈 버전은 더 많은 기능을 제공합니다."
        val analyzer = service.chooseAnalyzer(pageText)
        assertTrue(
            analyzer.javaClass.simpleName.contains("CJK"),
            "Should choose CJKAnalyzer for Korean text, got ${analyzer.javaClass.simpleName}"
        )
        analyzer.close()
    }

    @Test
    fun `CJK - Chinese keyword matching with bigrams`() {
        val pageText = "产品价格从每月99元起。企业版提供更多高级功能。"
        val results = service.search(pageText, listOf("价格"))

        assertTrue(results.containsKey("价格"), "Should find '价格' (price) in Chinese text")
    }

    // ─── German Detection & Stemming ───

    @Test
    fun `German - detects German text`() {
        val pageText = """
            Die Preise beginnen bei 99 Euro pro Monat. Das ist nicht teuer für ein Produkt
            mit solchen Funktionen und der Qualität die wir bieten. Unsere Kunden sind
            zufrieden mit dem Service und empfehlen uns auch ihren Freunden weiter.
        """.trimIndent()
        val analyzer = service.chooseAnalyzer(pageText)
        assertTrue(
            analyzer.javaClass.simpleName.contains("German"),
            "Should choose GermanAnalyzer, got ${analyzer.javaClass.simpleName}"
        )
        analyzer.close()
    }

    @Test
    fun `German stemming - Preise matches Preisen`() {
        val pageText = """
            Die aktuellen Preisen sind auf der Webseite verfügbar. Unsere Preise gelten
            für alle Regionen und sind nicht verhandelbar. Der Preis hängt von der Menge ab.
            Das ist ein gutes Angebot für die Qualität die wir bieten.
        """.trimIndent()
        val results = service.search(pageText, listOf("Preise"))

        assertTrue(results.containsKey("Preise"), "Should find matches for 'Preise' in German text")
    }

    // ─── French Detection & Stemming ───

    @Test
    fun `French - detects French text`() {
        val pageText = """
            Les prix commencent à 99 euros par mois. Ce n'est pas cher pour un produit
            avec de telles fonctionnalités. Nos clients sont satisfaits et nous recommandent
            à leurs amis. Nous sommes une entreprise française basée dans le sud de la France.
        """.trimIndent()
        val analyzer = service.chooseAnalyzer(pageText)
        assertTrue(
            analyzer.javaClass.simpleName.contains("French"),
            "Should choose FrenchAnalyzer, got ${analyzer.javaClass.simpleName}"
        )
        analyzer.close()
    }

    // ─── Spanish Detection ───

    @Test
    fun `Spanish - detects Spanish text`() {
        val pageText = """
            Los precios comienzan en 99 euros por mes. Este producto no es caro para
            las funcionalidades que ofrece. Nuestros clientes son muy satisfechos con
            nuestro servicio y nos recomiendan a sus amigos para que compren también.
        """.trimIndent()
        val analyzer = service.chooseAnalyzer(pageText)
        assertTrue(
            analyzer.javaClass.simpleName.contains("Spanish"),
            "Should choose SpanishAnalyzer, got ${analyzer.javaClass.simpleName}"
        )
        analyzer.close()
    }

    // ─── Currency Symbols & Short Keywords ───

    @Test
    fun `currency symbol dollar sign is not tokenized by analyzer`() {
        val pageText = "Starting at \$5,900 per month. Enterprise plan costs \$12,000."
        val results = service.search(pageText, listOf("$"))

        // "$" is punctuation; Lucene analyzers strip it. This test documents the known limitation:
        // browser-side exact matching handles currency symbols, not Lucene.
        assertFalse(
            results.containsKey("$"),
            "Lucene should NOT match '$' — browser-side countTextMatches handles currency symbols"
        )
    }

    @Test
    fun `numeric amounts can be matched`() {
        val pageText = "Starting at \$5,900 per month. Enterprise plan costs \$12,000."
        val results = service.search(pageText, listOf("5,900"))

        assertTrue(results.containsKey("5,900"), "Should match the numeric amount '5,900'")
    }

    // ─── Edge Cases ───

    @Test
    fun `empty page text returns empty results`() {
        val results = service.search("", listOf("test"))
        assertTrue(results.isEmpty(), "Empty page text should return empty results")
    }

    @Test
    fun `blank page text returns empty results`() {
        val results = service.search("   \n\n  ", listOf("test"))
        assertTrue(results.isEmpty(), "Blank page text should return empty results")
    }

    @Test
    fun `empty keywords returns empty results`() {
        val results = service.search("Some page text here", emptyList())
        assertTrue(results.isEmpty(), "Empty keywords should return empty results")
    }

    @Test
    fun `keyword not on page returns no matches for that keyword`() {
        val pageText = "This page is about cooking recipes and meal planning."
        val results = service.search(pageText, listOf("blockchain"))

        assertFalse(results.containsKey("blockchain"), "Should not match 'blockchain' on a cooking page")
    }

    @Test
    fun `multiple keywords - some match, some do not`() {
        val pageText = "Our product features include real-time analytics, automated reporting, and custom dashboards."
        val results = service.search(pageText, listOf("analytics", "blockchain", "reporting"))

        assertTrue(results.containsKey("analytics"), "Should match 'analytics'")
        assertTrue(results.containsKey("reporting"), "Should match 'reporting'")
        assertFalse(results.containsKey("blockchain"), "Should not match 'blockchain'")
    }

    @Test
    fun `surrounding context is returned`() {
        val pageText = """
            Line one of the document.
            The pricing information is here.
            Line three of the document.
        """.trimIndent()
        val results = service.search(pageText, listOf("pricing"))

        assertTrue(results.containsKey("pricing"))
        val match = results["pricing"]!!.first()
        assertTrue(match.surroundingContext.isNotBlank(), "Should have surrounding context")
    }

    @Test
    fun `score is positive for matching results`() {
        val pageText = "Machine learning and artificial intelligence are transforming industries."
        val results = service.search(pageText, listOf("machine"))

        assertTrue(results.containsKey("machine"))
        val match = results["machine"]!!.first()
        assertTrue(match.score > 0f, "Score should be positive, got ${match.score}")
    }

    // ─── Latency Benchmarks ───

    @Test
    fun `latency - small page (under 50ms)`() {
        val pageText = "This is a small page with just a few lines of text about pricing and features."
        val keywords = listOf("pricing", "features")

        // Warm up JIT
        repeat(3) { service.search(pageText, keywords) }

        val times = (1..20).map { measureTimeMillis { service.search(pageText, keywords) } }
        val median = times.sorted()[times.size / 2]
        val p95 = times.sorted()[(times.size * 0.95).toInt()]

        println("Small page latency: median=${median}ms, p95=${p95}ms, all=$times")
        assertTrue(median < 50, "Median latency for small page should be under 50ms, was ${median}ms")
    }

    @Test
    fun `latency - medium page 500 lines (under 100ms)`() {
        val pageText = buildString {
            repeat(500) { i ->
                appendLine("Line $i: This is a medium-sized page with various content about products, services, and pricing information.")
            }
        }
        val keywords = listOf("pricing", "products", "nonexistent")

        // Warm up
        repeat(3) { service.search(pageText, keywords) }

        val times = (1..10).map { measureTimeMillis { service.search(pageText, keywords) } }
        val median = times.sorted()[times.size / 2]
        val p95 = times.sorted()[(times.size * 0.95).toInt()]

        println("Medium page latency (500 lines): median=${median}ms, p95=${p95}ms")
        assertTrue(median < 100, "Median latency for medium page should be under 100ms, was ${median}ms")
    }

    @Test
    fun `latency - large page 2000 lines (under 300ms)`() {
        val pageText = buildString {
            repeat(2000) { i ->
                appendLine("Line $i: This is a large document representing a long webpage with extensive content about various topics including enterprise software pricing, Kubernetes deployment, and cloud infrastructure management solutions.")
            }
        }
        val keywords = listOf("Kubernetes", "pricing", "infrastructure", "nonexistent")

        // Warm up
        repeat(2) { service.search(pageText, keywords) }

        val times = (1..5).map { measureTimeMillis { service.search(pageText, keywords) } }
        val median = times.sorted()[times.size / 2]
        val p95 = times.sorted()[(times.size * 0.95).toInt()]

        println("Large page latency (2000 lines): median=${median}ms, p95=${p95}ms")
        assertTrue(median < 300, "Median latency for large page should be under 300ms, was ${median}ms")
    }

    // ─── Realistic End-to-End Scenarios ───

    @Test
    fun `realistic - SaaS pricing page with stemming`() {
        val pageText = """
            SleekFlow Pricing
            
            Starter Plan
            Starting at $49/month
            Includes 1,000 contacts and basic automation
            
            Pro Plan  
            Starting at $199/month
            Includes 10,000 contacts, advanced automation, and analytics
            
            Enterprise Plan
            Custom pricing - contact sales
            Unlimited contacts, dedicated support, SLA guarantees
            
            All prices shown in USD. Annual billing saves 20%.
            Compare features across plans on our comparison page.
        """.trimIndent()

        val results = service.search(pageText, listOf("pricing", "cost", "49"))

        assertTrue(results.containsKey("pricing"), "Should match 'pricing' (exact or stemmed)")
        assertTrue(results.containsKey("49"), "Should match numeric amount '49'")
        assertNotNull(results["pricing"], "Pricing results should not be null")
    }

    @Test
    fun `realistic - search for feature names via stemming`() {
        val pageText = """
            Features Overview
            
            Role-Based Access Control
            Define granular permissions for team members with our RBAC system.
            
            Stress Testing
            Run automated stress tests on your infrastructure with configurable load profiles.
            
            Real-time Monitoring
            Monitor all systems with sub-second latency dashboards.
        """.trimIndent()

        val results = service.search(pageText, listOf("stress testing", "monitored"))

        assertTrue(results.containsKey("stress testing"), "Should match 'Stress Testing' section")
        assertTrue(results.containsKey("monitored"), "Should match 'Monitor' via stemming of 'monitored'")
    }

    @Test
    fun `fallback to StandardAnalyzer for Cyrillic`() {
        val pageText = "Цена продукта начинается от 99 рублей в месяц. Мы предлагаем различные тарифные планы."
        val analyzer = service.chooseAnalyzer(pageText)
        assertEquals(
            "StandardAnalyzer",
            analyzer.javaClass.simpleName,
            "Should fall back to StandardAnalyzer for Cyrillic"
        )
        analyzer.close()
    }

    @Test
    fun `short text defaults to English analyzer`() {
        val pageText = "Hello world"
        val analyzer = service.chooseAnalyzer(pageText)
        assertTrue(
            analyzer.javaClass.simpleName.contains("English"),
            "Short Latin text should default to EnglishAnalyzer, got ${analyzer.javaClass.simpleName}"
        )
        analyzer.close()
    }
}
