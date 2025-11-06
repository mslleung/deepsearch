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
    
    data class Pdf(
        val finalUrl: String,
        val bytes: ByteArray,
        val statusCode: Int,
        val reasonPhrase: String,
        val mimeType: String
    ) : ContentTypeResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Pdf

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
}

/**
 * Service for resolving HTTP content types and downloading content.
 * Uses HEAD request first for efficiency, falls back to GET for PDFs.
 */
class HttpContentTypeResolutionService : IHttpContentTypeResolutionService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    
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
            
            logger.debug(
                "HEAD response for {}: status={}, contentType={}",
                url,
                statusCode,
                contentType
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
                contentType.contains("text/html", ignoreCase = true) ||
                contentType.contains("application/xhtml", ignoreCase = true) -> {
                    ContentTypeResult.Html(
                        finalUrl = finalUrl,
                        statusCode = statusCode,
                        reasonPhrase = reasonPhrase,
                        mimeType = contentType
                    )
                }
                contentType.contains("application/pdf", ignoreCase = true) -> {
                    // For PDF, we need to download the content
                    logger.debug("Downloading PDF from: {}", finalUrl)
                    val getResponse = client.get(finalUrl)
                    val pdfBytes = getResponse.readRawBytes()
                    
                    ContentTypeResult.Pdf(
                        finalUrl = finalUrl,
                        bytes = pdfBytes,
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

