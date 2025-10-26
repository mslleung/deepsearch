package io.deepsearch.application.services

import io.deepsearch.domain.agents.IPdfToMarkdownAgent
import io.deepsearch.domain.agents.PdfToMarkdownInput
import io.deepsearch.domain.models.entities.PdfMarkdown
import io.deepsearch.domain.repositories.IPdfMarkdownRepository
import org.apache.pdfbox.Loader
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import kotlin.io.encoding.Base64

interface IPdfConversionService {
    suspend fun convertPdfToMarkdown(pdfBytes: ByteArray): String
}

/**
 * Service for converting PDF documents to markdown format.
 * Validates PDF against Gemini constraints (max 1000 pages, max 50MB) and caches results by PDF hash.
 */
class PdfConversionService(
    private val pdfToMarkdownAgent: IPdfToMarkdownAgent,
    private val pdfMarkdownRepository: IPdfMarkdownRepository
) : IPdfConversionService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val MAX_PAGES = 1000
        private const val MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024 // 50MB
    }

    override suspend fun convertPdfToMarkdown(pdfBytes: ByteArray): String {
        // Calculate PDF hash for caching
        val pdfHash = calculateHash(pdfBytes)
        
        // Check cache first
        val cached = pdfMarkdownRepository.findByHash(pdfHash)
        if (cached != null) {
            logger.debug("Cache hit for PDF hash: {}", pdfHash)
            return cached.markdown
        }
        
        logger.debug("Cache miss for PDF hash: {}, validating PDF", pdfHash)
        
        // Validate PDF before sending to Gemini
        val validation = validatePdf(pdfBytes)
        if (!validation.isValid) {
            logger.warn("PDF validation failed: {}", validation.reason)
            return "" // Return empty markdown for invalid PDFs
        }
        
        logger.debug(
            "PDF valid: {} pages, {} bytes, converting to markdown",
            validation.pageCount,
            pdfBytes.size
        )
        
        // Convert PDF to markdown using agent
        val output = pdfToMarkdownAgent.generate(PdfToMarkdownInput(pdfBytes))
        
        // Cache the result
        pdfMarkdownRepository.upsert(
            PdfMarkdown(
                pdfHash = pdfHash,
                markdown = output.markdown,
                pageCount = validation.pageCount ?: 0,
                fileSizeBytes = pdfBytes.size.toLong()
            )
        )
        
        logger.debug("Cached PDF markdown for hash: {}", pdfHash)
        
        return output.markdown
    }
    
    private fun calculateHash(pdfBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(pdfBytes)
        return Base64.encode(hashBytes)
    }
    
    private data class PdfValidation(
        val isValid: Boolean,
        val reason: String? = null,
        val pageCount: Int? = null
    )
    
    private fun validatePdf(pdfBytes: ByteArray): PdfValidation {
        // Check file size first (cheap check)
        if (pdfBytes.size > MAX_FILE_SIZE_BYTES) {
            return PdfValidation(
                isValid = false,
                reason = "PDF size ${pdfBytes.size} bytes exceeds maximum ${MAX_FILE_SIZE_BYTES} bytes"
            )
        }
        
        // Load PDF and check page count
        return try {
            val document = Loader.loadPDF(pdfBytes)
            val pageCount = document.numberOfPages
            document.close()
            
            if (pageCount > MAX_PAGES) {
                PdfValidation(
                    isValid = false,
                    reason = "PDF has $pageCount pages, exceeds maximum $MAX_PAGES pages",
                    pageCount = pageCount
                )
            } else {
                PdfValidation(
                    isValid = true,
                    pageCount = pageCount
                )
            }
        } catch (e: Exception) {
            PdfValidation(
                isValid = false,
                reason = "Failed to load PDF: ${e.message}"
            )
        }
    }
}

