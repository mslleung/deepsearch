package io.deepsearch.infrastructure.storage

import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.services.IIterationScreenshotStorage
import io.deepsearch.domain.services.IterationScreenshotPath
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Local filesystem implementation of [IIterationScreenshotStorage] for test/benchmark use.
 * Saves screenshots to a configurable root directory for visual inspection.
 */
class LocalIterationScreenshotStorage(
    private val rootDir: Path = Paths.get("/tmp/benchmark-screenshots")
) : IIterationScreenshotStorage {

    private val logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun saveRawScreenshot(
        sessionId: SessionId,
        url: String,
        iteration: Int,
        bytes: ByteArray,
        mimeType: ImageMimeType
    ) {
        val path = IterationScreenshotPath.rawPath(sessionId, url, iteration, mimeType)
        write(path, bytes)
    }

    override suspend fun saveAnnotatedScreenshot(
        sessionId: SessionId,
        url: String,
        iteration: Int,
        bytes: ByteArray
    ) {
        val path = IterationScreenshotPath.annotatedPath(sessionId, url, iteration)
        write(path, bytes)
    }

    override suspend fun saveRegionCrop(
        sessionId: SessionId,
        url: String,
        iteration: Int,
        regionIndex: Int,
        bytes: ByteArray,
        description: String
    ) {
        val path = IterationScreenshotPath.regionCropPath(sessionId, url, iteration, regionIndex)
        write(path, bytes)
    }

    private fun write(relativePath: String, bytes: ByteArray) {
        try {
            val file = rootDir.resolve(relativePath)
            Files.createDirectories(file.parent)
            Files.write(file, bytes)
            logger.info("Saved screenshot: {} ({} bytes)", file, bytes.size)
        } catch (e: Exception) {
            logger.warn("Failed to save screenshot {}: {}", relativePath, e.message)
        }
    }
}
