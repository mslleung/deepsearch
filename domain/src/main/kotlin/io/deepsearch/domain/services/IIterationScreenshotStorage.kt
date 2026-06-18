package io.deepsearch.domain.services

import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.SessionId

/**
 * Storage port for agentic navigation iteration screenshots.
 *
 * Saves raw, annotated, and region-crop screenshots produced during each
 * iteration of the navigation loop. Implementations are fire-and-forget:
 * errors are logged but never propagated, so navigation is never blocked.
 *
 * Path layout (same for local and cloud):
 *   {root}/{sessionId}/{urlHash}/iter-{nn}-{type}.{ext}
 *
 * In tests, {root} is a local directory; in production, a GCS prefix.
 */
interface IIterationScreenshotStorage {

    /** Full-page screenshot captured from the browser (PNG). */
    suspend fun saveRawScreenshot(
        sessionId: SessionId,
        url: String,
        iteration: Int,
        bytes: ByteArray,
        mimeType: ImageMimeType
    )

    /** Downscaled JPEG with element-label badges overlaid. */
    suspend fun saveAnnotatedScreenshot(
        sessionId: SessionId,
        url: String,
        iteration: Int,
        bytes: ByteArray
    )

    /** Cropped region from visual segmentation. */
    suspend fun saveRegionCrop(
        sessionId: SessionId,
        url: String,
        iteration: Int,
        regionIndex: Int,
        bytes: ByteArray,
        description: String
    )
}
