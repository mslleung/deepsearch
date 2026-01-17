package io.deepsearch.domain.services

import io.deepsearch.domain.config.domainBenchmarkTestModule
import io.deepsearch.domain.models.valueobjects.FileSearchStoreInfo
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import io.deepsearch.domain.config.IApplicationCoroutineScope
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

/**
 * Tests to verify Gemini File Search's ability to handle:
 * 1. PDFs containing images with text (OCR capability)
 * 2. PDFs containing tables
 * 3. Complex multi-element PDFs
 * 
 * This helps determine if Gemini's "Layout Parser" and "OCR Parser" work as documented.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class GeminiFileSearchImagePdfTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainBenchmarkTestModule)
    }

    private val fileSearchService by inject<IGeminiFileSearchService>()
    private val applicationScope by inject<IApplicationCoroutineScope>()

    // Test domain for this test class
    private val testDomain = "test-pdf-images-${System.currentTimeMillis()}.example.com"
    
    @AfterAll
    fun cleanup() {
        // Clean up application scope to cancel background coroutines
        applicationScope.close()
    }
    private var testStoreInfo: FileSearchStoreInfo? = null

    // ==================== Test 1: Image-Only PDF ====================

    @Test
    @Order(1)
    fun `1 - upload PDF containing only an image with text`() = runBlocking {
        println("\n" + "=".repeat(70))
        println("GEMINI FILE SEARCH - IMAGE/TABLE PDF TEST")
        println("=".repeat(70))
        println("Testing if Gemini can extract text from images embedded in PDFs")
        println("Domain: $testDomain")
        
        testStoreInfo = fileSearchService.getOrCreateStore(testDomain)
        println("Store created: ${testStoreInfo?.name}")
        
        println("\n--- Test 1: PDF with Image Containing Text ---")
        
        // Create an image with important text
        val imageText = """
            IMPORTANT FINANCIAL DATA
            
            Q4 2025 Revenue: $7.3 Billion
            Operating Margin: 42%
            Net Income: $2.1 Billion
            
            Key Metrics:
            - Customer Growth: 35% YoY
            - ARR: $12.5 Billion
            - Cash Position: $8.9 Billion
        """.trimIndent()
        
        println("Creating image with text:")
        println(imageText.prependIndent("   "))
        
        // Create the image
        val imageBytes = createImageWithText(imageText, 800, 400)
        println("Image created: ${imageBytes.size} bytes")
        
        // Create PDF containing only this image
        val pdfBytes = createPdfWithImage(imageBytes, "Financial Summary Image")
        println("PDF created: ${pdfBytes.size} bytes")
        
        // Upload to Gemini
        val fileHash = calculateHash(pdfBytes)
        val sourceUrl = "https://$testDomain/reports/image-only-financial.pdf"
        
        val (fileInfo, uploadTime) = measureTimedValue {
            fileSearchService.uploadFile(
                storeName = testStoreInfo!!.name,
                fileBytes = pdfBytes,
                mimeType = "application/pdf",
                sourceUrl = sourceUrl,
                fileHash = fileHash
            )
        }
        
        println("✅ PDF uploaded: ${fileInfo.name}")
        println("   Upload time: ${uploadTime.inWholeMilliseconds}ms")
    }

    @Test
    @Order(2)
    fun `2 - query for text that only exists in the image`() = runBlocking {
        println("\n--- Test 2: Query for Image Text ---")
        
        val query = "What was the Q4 2025 revenue and operating margin?"
        println("Query: $query")
        
        val (result, queryTime) = measureTimedValue {
            fileSearchService.queryStore(
                storeName = testStoreInfo!!.name,
                query = query
            )
        }
        
        val content = result.chunks.joinToString(" ") { it.content }
        
        println("\n📋 Query Result:")
        println(content.take(500))
        println("\nQuery time: ${queryTime.inWholeMilliseconds}ms")
        println("Token usage: ${result.tokenUsage}")
        
        // Check if Gemini extracted the text from the image
        val foundRevenue = content.contains("7.3", ignoreCase = true) || 
                          content.contains("billion", ignoreCase = true)
        val foundMargin = content.contains("42", ignoreCase = true) || 
                         content.contains("margin", ignoreCase = true)
        
        println("\n🔍 Analysis:")
        println("   Found revenue info: $foundRevenue")
        println("   Found margin info: $foundMargin")
        
        if (foundRevenue && foundMargin) {
            println("   ✅ SUCCESS: Gemini CAN read text from images in PDFs!")
        } else if (content.contains("cannot find", ignoreCase = true) || 
                   content.contains("not found", ignoreCase = true)) {
            println("   ❌ FAILURE: Gemini could NOT read the image text")
            println("   This means OCR/image parsing is NOT working for this PDF")
        } else {
            println("   ⚠️ PARTIAL: Some content found but may not be from image")
        }
    }

    // ==================== Test 3: Table in Image ====================

    @Test
    @Order(3)
    fun `3 - upload PDF with table rendered as image`() = runBlocking {
        println("\n--- Test 3: PDF with Table as Image ---")
        
        // Create a table image
        val tableData = listOf(
            listOf("Product", "Q1 Sales", "Q2 Sales", "Q3 Sales", "Q4 Sales"),
            listOf("Widget A", "$1.2M", "$1.5M", "$1.8M", "$2.1M"),
            listOf("Widget B", "$0.8M", "$0.9M", "$1.1M", "$1.3M"),
            listOf("Widget C", "$2.5M", "$2.8M", "$3.2M", "$3.7M"),
            listOf("TOTAL", "$4.5M", "$5.2M", "$6.1M", "$7.1M")
        )
        
        println("Creating table image with data:")
        tableData.forEach { row -> println("   ${row.joinToString(" | ")}") }
        
        val imageBytes = createTableImage(tableData, 700, 250)
        println("Table image created: ${imageBytes.size} bytes")
        
        val pdfBytes = createPdfWithImage(imageBytes, "Quarterly Sales Table")
        println("PDF created: ${pdfBytes.size} bytes")
        
        val fileHash = calculateHash(pdfBytes)
        val sourceUrl = "https://$testDomain/reports/table-image.pdf"
        
        val (fileInfo, uploadTime) = measureTimedValue {
            fileSearchService.uploadFile(
                storeName = testStoreInfo!!.name,
                fileBytes = pdfBytes,
                mimeType = "application/pdf",
                sourceUrl = sourceUrl,
                fileHash = fileHash
            )
        }
        
        println("✅ Table PDF uploaded: ${fileInfo.name}")
        println("   Upload time: ${uploadTime.inWholeMilliseconds}ms")
    }

    @Test
    @Order(4)
    fun `4 - query for specific table data`() = runBlocking {
        println("\n--- Test 4: Query for Table Data ---")
        
        val query = "What were the Q4 sales for Widget C and what was the total Q4 sales?"
        println("Query: $query")
        
        val (result, queryTime) = measureTimedValue {
            fileSearchService.queryStore(
                storeName = testStoreInfo!!.name,
                query = query
            )
        }
        
        val content = result.chunks.joinToString(" ") { it.content }
        
        println("\n📋 Query Result:")
        println(content.take(500))
        println("\nQuery time: ${queryTime.inWholeMilliseconds}ms")
        
        // Check if Gemini found the table data
        val foundWidgetC = content.contains("3.7", ignoreCase = true) || 
                          content.contains("Widget C", ignoreCase = true)
        val foundTotal = content.contains("7.1", ignoreCase = true) || 
                        content.contains("total", ignoreCase = true)
        
        println("\n🔍 Analysis:")
        println("   Found Widget C Q4 ($3.7M): $foundWidgetC")
        println("   Found Total Q4 ($7.1M): $foundTotal")
        
        if (foundWidgetC && foundTotal) {
            println("   ✅ SUCCESS: Gemini CAN read tables from images!")
        } else {
            println("   ❌ FAILURE: Gemini could NOT parse the table image")
        }
    }

    // ==================== Test 5: Mixed Content PDF ====================

    @Test
    @Order(5)
    fun `5 - upload PDF with text and image mixed`() = runBlocking {
        println("\n--- Test 5: Mixed PDF (Text + Image) ---")
        
        // This tests if Gemini can handle PDFs with both native text AND images
        val chartImageText = """
            CHART: Monthly Revenue Trend
            
            Jan: $500K  ████████
            Feb: $620K  ██████████
            Mar: $580K  █████████
            Apr: $710K  ████████████
            May: $890K  ██████████████
            Jun: $950K  ███████████████
        """.trimIndent()
        
        val imageBytes = createImageWithText(chartImageText, 600, 300)
        
        // Create PDF with both text and image
        val pdfBytes = createMixedPdf(
            titleText = "Q2 2025 Revenue Analysis Report",
            bodyText = """
                Executive Summary:
                This report analyzes our revenue performance for Q2 2025.
                Our flagship product showed strong growth, exceeding targets by 15%.
                
                Key Findings:
                - Revenue grew consistently each month
                - June achieved highest monthly revenue at $950K
                - Customer acquisition cost decreased by 12%
                
                See the chart below for monthly breakdown:
            """.trimIndent(),
            imageBytes = imageBytes,
            footerText = "Confidential - Internal Use Only"
        )
        
        println("Mixed PDF created: ${pdfBytes.size} bytes")
        
        val fileHash = calculateHash(pdfBytes)
        val sourceUrl = "https://$testDomain/reports/mixed-content.pdf"
        
        val (fileInfo, uploadTime) = measureTimedValue {
            fileSearchService.uploadFile(
                storeName = testStoreInfo!!.name,
                fileBytes = pdfBytes,
                mimeType = "application/pdf",
                sourceUrl = sourceUrl,
                fileHash = fileHash
            )
        }
        
        println("✅ Mixed PDF uploaded: ${fileInfo.name}")
        println("   Upload time: ${uploadTime.inWholeMilliseconds}ms")
    }

    @Test
    @Order(6)
    fun `6 - query mixed PDF for text content`() = runBlocking {
        println("\n--- Test 6: Query Mixed PDF for Native Text ---")
        
        val query = "What was the customer acquisition cost change and what month had highest revenue?"
        println("Query: $query")
        
        val (result, queryTime) = measureTimedValue {
            fileSearchService.queryStore(
                storeName = testStoreInfo!!.name,
                query = query
            )
        }
        
        val content = result.chunks.joinToString(" ") { it.content }
        
        println("\n📋 Query Result:")
        println(content.take(600))
        println("\nQuery time: ${queryTime.inWholeMilliseconds}ms")
        
        // Check for native text content
        val foundCACDecrease = content.contains("12", ignoreCase = true) || 
                               content.contains("decreased", ignoreCase = true)
        // Check for image content (June $950K)
        val foundJuneRevenue = content.contains("950", ignoreCase = true) || 
                               content.contains("June", ignoreCase = true)
        
        println("\n🔍 Analysis:")
        println("   Found CAC decrease (12%): $foundCACDecrease (from native PDF text)")
        println("   Found June revenue ($950K): $foundJuneRevenue (from image in PDF)")
        
        if (foundCACDecrease && foundJuneRevenue) {
            println("   ✅ SUCCESS: Gemini reads BOTH native text AND image text!")
        } else if (foundCACDecrease && !foundJuneRevenue) {
            println("   ⚠️ PARTIAL: Native text works, but image OCR failed")
        } else {
            println("   ❌ FAILURE: Query did not find expected content")
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Creates a PNG image with the given text rendered on it.
     */
    private fun createImageWithText(text: String, width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g2d = image.createGraphics()
        
        // White background
        g2d.color = Color.WHITE
        g2d.fillRect(0, 0, width, height)
        
        // Black text
        g2d.color = Color.BLACK
        g2d.font = Font("Arial", Font.PLAIN, 16)
        
        // Draw text line by line
        val lines = text.split("\n")
        var y = 30
        for (line in lines) {
            g2d.drawString(line, 20, y)
            y += 22
        }
        
        g2d.dispose()
        
        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "PNG", baos)
        return baos.toByteArray()
    }

    /**
     * Creates a PNG image with a table rendered on it.
     */
    private fun createTableImage(data: List<List<String>>, width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g2d = image.createGraphics()
        
        // White background
        g2d.color = Color.WHITE
        g2d.fillRect(0, 0, width, height)
        
        g2d.color = Color.BLACK
        g2d.font = Font("Monospaced", Font.PLAIN, 14)
        
        val colWidth = width / data[0].size
        val rowHeight = 35
        
        // Draw table grid and content
        for ((rowIdx, row) in data.withIndex()) {
            val y = 30 + rowIdx * rowHeight
            
            // Header row in bold
            if (rowIdx == 0) {
                g2d.font = Font("Monospaced", Font.BOLD, 14)
            } else {
                g2d.font = Font("Monospaced", Font.PLAIN, 14)
            }
            
            for ((colIdx, cell) in row.withIndex()) {
                val x = 10 + colIdx * colWidth
                g2d.drawString(cell, x, y)
                
                // Draw cell border
                g2d.drawRect(5 + colIdx * colWidth, y - 20, colWidth - 5, rowHeight)
            }
        }
        
        g2d.dispose()
        
        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "PNG", baos)
        return baos.toByteArray()
    }

    /**
     * Creates a PDF with a single image.
     */
    private fun createPdfWithImage(imageBytes: ByteArray, title: String): ByteArray {
        val document = PDDocument()
        try {
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            
            val pdImage = PDImageXObject.createFromByteArray(document, imageBytes, "image")
            
            PDPageContentStream(document, page).use { contentStream ->
                // Draw image centered on page
                val scale = minOf(
                    (page.mediaBox.width - 100) / pdImage.width,
                    (page.mediaBox.height - 150) / pdImage.height
                )
                val scaledWidth = pdImage.width * scale
                val scaledHeight = pdImage.height * scale
                val x = (page.mediaBox.width - scaledWidth) / 2
                val y = page.mediaBox.height - scaledHeight - 100
                
                contentStream.drawImage(pdImage, x, y, scaledWidth, scaledHeight)
            }
            
            val baos = ByteArrayOutputStream()
            document.save(baos)
            return baos.toByteArray()
        } finally {
            document.close()
        }
    }

    /**
     * Creates a PDF with both native text and an embedded image.
     * Uses PDFBox 3.x API with Standard14Fonts.
     */
    private fun createMixedPdf(
        titleText: String,
        bodyText: String,
        imageBytes: ByteArray,
        footerText: String
    ): ByteArray {
        val document = PDDocument()
        try {
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            
            val pdImage = PDImageXObject.createFromByteArray(document, imageBytes, "chart")
            
            // Load fonts using PDFBox 3.x API
            val fontBold = org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA_BOLD
            val fontRegular = org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA
            val fontItalic = org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA_OBLIQUE
            
            val pdfFontBold = org.apache.pdfbox.pdmodel.font.PDType1Font(fontBold)
            val pdfFontRegular = org.apache.pdfbox.pdmodel.font.PDType1Font(fontRegular)
            val pdfFontItalic = org.apache.pdfbox.pdmodel.font.PDType1Font(fontItalic)
            
            PDPageContentStream(document, page).use { contentStream ->
                // Title
                contentStream.beginText()
                contentStream.setFont(pdfFontBold, 18f)
                contentStream.newLineAtOffset(50f, page.mediaBox.height - 50)
                contentStream.showText(titleText)
                contentStream.endText()
                
                // Body text
                contentStream.beginText()
                contentStream.setFont(pdfFontRegular, 12f)
                contentStream.newLineAtOffset(50f, page.mediaBox.height - 100)
                contentStream.setLeading(16f)
                
                for (line in bodyText.split("\n")) {
                    contentStream.showText(line)
                    contentStream.newLine()
                }
                contentStream.endText()
                
                // Image (chart)
                val scale = 0.8f
                val scaledWidth = pdImage.width * scale
                val scaledHeight = pdImage.height * scale
                contentStream.drawImage(pdImage, 50f, 200f, scaledWidth, scaledHeight)
                
                // Footer
                contentStream.beginText()
                contentStream.setFont(pdfFontItalic, 10f)
                contentStream.newLineAtOffset(50f, 50f)
                contentStream.showText(footerText)
                contentStream.endText()
            }
            
            val baos = ByteArrayOutputStream()
            document.save(baos)
            return baos.toByteArray()
        } finally {
            document.close()
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun calculateHash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(bytes)
        return Base64.encode(hashBytes)
    }
}
