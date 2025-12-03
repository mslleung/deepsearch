package io.deepsearch.application.services

import io.deepsearch.domain.exceptions.*
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

interface IHttpContentTypeResolutionService {
    suspend fun resolve(url: String): ContentTypeResult
}

/**
 * Result of HTTP content type resolution.
 */
sealed class ContentTypeResult {
    data class Html(
        val finalUrl: String,
        val statusCode: Int,
        val reasonPhrase: String,
        val mimeType: String
    ) : ContentTypeResult()
    
    /**
     * A file type supported by Gemini File Search.
     * This includes PDFs, Office documents, text files, code files, and more.
     */
    data class SupportedFile(
        val finalUrl: String,
        val bytes: ByteArray,
        val statusCode: Int,
        val reasonPhrase: String,
        val mimeType: String
    ) : ContentTypeResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as SupportedFile

            if (finalUrl != other.finalUrl) return false
            if (!bytes.contentEquals(other.bytes)) return false
            if (statusCode != other.statusCode) return false
            if (reasonPhrase != other.reasonPhrase) return false
            if (mimeType != other.mimeType) return false

            return true
        }

        override fun hashCode(): Int {
            var result = finalUrl.hashCode()
            result = 31 * result + bytes.contentHashCode()
            result = 31 * result + statusCode
            result = 31 * result + reasonPhrase.hashCode()
            result = 31 * result + mimeType.hashCode()
            return result
        }
    }
    
    data class Unsupported(
        val finalUrl: String,
        val contentType: String,
        val statusCode: Int,
        val reasonPhrase: String
    ) : ContentTypeResult()
    
    /**
     * File exceeds the maximum size limit (25 MB).
     */
    data class FileTooLarge(
        val finalUrl: String,
        val contentLength: Long,
        val maxSizeBytes: Long,
        val mimeType: String
    ) : ContentTypeResult()
}

/**
 * MIME types supported by Gemini File Search.
 * See: https://ai.google.dev/gemini-api/docs/file-search
 */
object GeminiSupportedMimeTypes {
    // Application types
    private val APPLICATION_TYPES = setOf(
        "application/pdf",
        "application/json",
        "application/xml",
        "application/msword",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
        "application/vnd.oasis.opendocument.text",
        "application/vnd.jupyter",
        "application/sql",
        "application/typescript",
        "application/ecmascript",
        "application/dart",
        "application/vnd.dart",
        "application/x-sh",
        "application/x-shellscript",
        "application/x-csh",
        "application/x-zsh",
        "application/x-powershell",
        "application/x-php",
        "application/x-latex",
        "application/x-tex",
        "application/x-hwp",
        "application/x-hwp-v5",
        "application/zip"
    )

    // Text types
    private val TEXT_TYPES = setOf(
        "text/plain",
        "text/html",
        "text/css",
        "text/csv",
        "text/markdown",
        "text/xml",
        "text/javascript",
        "text/jsx",
        "text/tsx",
        "text/yaml",
        "text/x-python",
        "text/x-java",
        "text/x-java-source",
        "text/x-kotlin",
        "text/x-scala",
        "text/x-go",
        "text/x-rust",
        "text/x-swift",
        "text/x-csharp",
        "text/x-c",
        "text/x-c++src",
        "text/x-c++hdr",
        "text/x-csrc",
        "text/x-chdr",
        "text/x-ruby-script",
        "text/x-perl",
        "text/x-perl-script",
        "text/x-php",
        "text/x-sh",
        "text/x-sql",
        "text/x-diff",
        "text/x-haskell",
        "text/x-lua",
        "text/x-erlang",
        "text/x-lisp",
        "text/x-scheme",
        "text/x-pascal",
        "text/x-objcsrc",
        "text/rtf",
        "text/tab-separated-values",
        "text/tsv",
        "text/vtt"
    )

    /**
     * Check if a MIME type is supported by Gemini File Search.
     */
    fun isSupported(mimeType: String): Boolean {
        val normalizedMimeType = mimeType.lowercase().substringBefore(';').trim()
        
        // Check exact matches
        if (normalizedMimeType in APPLICATION_TYPES || normalizedMimeType in TEXT_TYPES) {
            return true
        }
        
        // Check prefix matches for text/* types (many code formats use text/x-* patterns)
        if (normalizedMimeType.startsWith("text/")) {
            return true
        }
        
        return false
    }
}

/**
 * Service for resolving HTTP content types and downloading content.
 * Uses HEAD request first for efficiency, falls back to GET for supported files.
 */
class HttpContentTypeResolutionService : IHttpContentTypeResolutionService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    
    companion object {
        /**
         * Maximum file size for processing: 25 MB.
         * Files larger than this are skipped to avoid memory issues and API limits.
         */
        const val MAX_FILE_SIZE_BYTES = 25L * 1024 * 1024
    }
    
    private val client = HttpClient(OkHttp) {
        followRedirects = true
        expectSuccess = false // Don't throw on non-2xx responses
    }

    override suspend fun resolve(url: String): ContentTypeResult {
        logger.debug("Resolving content type for URL: {}", url)
        
        try {
            // Try HEAD request first to check content type without downloading
            val headResponse = client.head(url)
            val finalUrl = headResponse.request.url.toString()
            val statusCode = headResponse.status.value
            val reasonPhrase = headResponse.status.description
            val contentType = headResponse.contentType()?.toString() ?: "unknown"
            val contentLength = headResponse.contentLength()
            
            logger.debug(
                "HEAD response for {}: status={}, contentType={}, contentLength={}",
                url,
                statusCode,
                contentType,
                contentLength
            )
            
            // Throw exception if request failed
            if (statusCode !in 200..299) {
                if (statusCode in 400..499) {
                    throw HttpClientErrorException(finalUrl, statusCode, reasonPhrase)
                } else if (statusCode in 500..599) {
                    throw HttpServerErrorException(finalUrl, statusCode, reasonPhrase)
                } else {
                    throw HttpClientErrorException(finalUrl, statusCode, reasonPhrase)
                }
            }
            
            // Route based on content type
            return when {
                // HTML content - process with browser
                contentType.contains("text/html", ignoreCase = true) ||
                contentType.contains("application/xhtml", ignoreCase = true) -> {
                    ContentTypeResult.Html(
                        finalUrl = finalUrl,
                        statusCode = statusCode,
                        reasonPhrase = reasonPhrase,
                        mimeType = contentType
                    )
                }
                // Supported file types for Gemini File Search
                GeminiSupportedMimeTypes.isSupported(contentType) -> {
                    // Check content length from HEAD response if available
                    if (contentLength != null && contentLength > MAX_FILE_SIZE_BYTES) {
                        logger.warn("File too large ({} bytes > {} bytes): {}", contentLength, MAX_FILE_SIZE_BYTES, finalUrl)
                        return ContentTypeResult.FileTooLarge(
                            finalUrl = finalUrl,
                            contentLength = contentLength,
                            maxSizeBytes = MAX_FILE_SIZE_BYTES,
                            mimeType = contentType
                        )
                    }
                    
                    logger.debug("Downloading supported file from: {} (type: {})", finalUrl, contentType)
                    val getResponse = client.get(finalUrl)
                    val fileBytes = getResponse.readRawBytes()
                    
                    // Double-check actual size after download
                    if (fileBytes.size > MAX_FILE_SIZE_BYTES) {
                        logger.warn("Downloaded file too large ({} bytes > {} bytes): {}", fileBytes.size, MAX_FILE_SIZE_BYTES, finalUrl)
                        return ContentTypeResult.FileTooLarge(
                            finalUrl = finalUrl,
                            contentLength = fileBytes.size.toLong(),
                            maxSizeBytes = MAX_FILE_SIZE_BYTES,
                            mimeType = contentType
                        )
                    }
                    
                    ContentTypeResult.SupportedFile(
                        finalUrl = finalUrl,
                        bytes = fileBytes,
                        statusCode = statusCode,
                        reasonPhrase = reasonPhrase,
                        mimeType = contentType
                    )
                }
                else -> {
                    ContentTypeResult.Unsupported(
                        finalUrl = finalUrl,
                        contentType = contentType,
                        statusCode = statusCode,
                        reasonPhrase = reasonPhrase
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UrlProcessingException) {
            // Already a typed exception, rethrow as-is
            throw e
        } catch (e: UnknownHostException) {
            logger.error("DNS resolution failed for {}: {}", url, e.message)
            throw DnsResolutionException(url, e)
        } catch (e: ConnectException) {
            logger.error("Connection refused for {}: {}", url, e.message)
            throw ConnectionRefusedException(url, e)
        } catch (e: SocketTimeoutException) {
            logger.error("Network timeout for {}: {}", url, e.message)
            throw NetworkTimeoutException(url, e)
        } catch (e: SSLException) {
            logger.error("SSL handshake failed for {}: {}", url, e.message)
            throw SslHandshakeException(url, e)
        } catch (e: Exception) {
            logger.error("Failed to resolve content type for {}: {}", url, e.message)
            // Wrap unexpected exceptions in a generic browser navigation exception
            throw BrowserNavigationException(url, e)
        }
    }
}

