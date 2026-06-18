package io.deepsearch.domain.services

import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.SessionId
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Deterministic GCS path computation for iteration screenshots.
 *
 * Shared between [IIterationScreenshotStorage] implementations (which write bytes)
 * and the application layer (which persists path metadata to the DB).
 *
 * Path layout: `agentic-nav/{sessionId}/{urlHash}/iter-{nn}-{suffix}`
 */
@OptIn(ExperimentalEncodingApi::class)
object IterationScreenshotPath {

    fun rawPath(sessionId: SessionId, url: String, iteration: Int, mimeType: ImageMimeType): String =
        buildPath(sessionId, url, iteration, "raw.${extensionFor(mimeType)}")

    fun annotatedPath(sessionId: SessionId, url: String, iteration: Int): String =
        buildPath(sessionId, url, iteration, "annotated.jpeg")

    fun regionCropPath(sessionId: SessionId, url: String, iteration: Int, regionIndex: Int): String =
        buildPath(sessionId, url, iteration, "region-$regionIndex.png")

    fun buildPath(sessionId: SessionId, url: String, iteration: Int, suffix: String): String {
        val hash = urlHash(url)
        val fileName = "iter-%02d-%s".format(iteration, suffix)
        return "agentic-nav/${sessionId.value}/$hash/$fileName"
    }

    fun urlHash(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        return Base64.UrlSafe.encode(digest).take(12)
    }

    private fun extensionFor(mimeType: ImageMimeType): String = when (mimeType) {
        ImageMimeType.PNG -> "png"
        ImageMimeType.JPEG -> "jpeg"
        ImageMimeType.WEBP -> "webp"
        else -> "bin"
    }
}
