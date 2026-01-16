package io.deepsearch.domain.services

import org.slf4j.LoggerFactory
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Service for extracting image dimensions and scaling images.
 * Uses TwelveMonkeys ImageIO for WebP support.
 */
interface IImageDimensionService {
    /**
     * Extract image dimensions from bytes.
     * Supports common formats including PNG, JPEG, and WebP.
     * 
     * @param imageBytes The image data
     * @return Pair of (width, height)
     * @throws ImageDimensionException if extraction fails
     */
    fun getImageDimensions(imageBytes: ByteArray): Pair<Int, Int>
    
    /**
     * Result of scaling an image for Gemini API processing.
     * 
     * @property scaledBytes The scaled image bytes (JPEG format for size efficiency)
     * @property scaledMimeType The MIME type of the scaled image
     * @property scaleFactor The scale factor applied (1.0 if no scaling needed)
     * @property originalWidth Original image width
     * @property originalHeight Original image height
     * @property wasScaled Whether the image was actually scaled down
     */
    data class ScaledImageResult(
        val scaledBytes: ByteArray,
        val scaledMimeType: String,
        val scaleFactor: Double,
        val originalWidth: Int,
        val originalHeight: Int,
        val wasScaled: Boolean
    )
    
    /**
     * Scale an image down if it exceeds the maximum dimension for Gemini API.
     * 
     * Gemini can process images up to 20MB, but images larger than ~4096px in any dimension
     * may cause "Unable to process input image" errors. This method scales down large images
     * while preserving aspect ratio.
     * 
     * For full-page screenshots that may be very tall (e.g., Wikipedia articles at 20,000+ px),
     * this ensures Gemini can process them for semantic identification while the original
     * full-resolution image is preserved for accurate element cropping.
     * 
     * @param imageBytes The original image bytes
     * @param maxDimension Maximum dimension (width or height) allowed. Default 4096px.
     * @return ScaledImageResult containing scaled bytes and metadata for coordinate adjustment
     */
    fun scaleImageForGemini(imageBytes: ByteArray, maxDimension: Int = 4096): ScaledImageResult
}

class ImageDimensionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class ImageDimensionService : IImageDimensionService {
    
    private val logger = LoggerFactory.getLogger(this::class.java)
    
    override fun getImageDimensions(imageBytes: ByteArray): Pair<Int, Int> {
        if (imageBytes.isEmpty()) {
            throw ImageDimensionException("Image bytes are empty")
        }
        
        val inputStream = ByteArrayInputStream(imageBytes)
        val imageInputStream = ImageIO.createImageInputStream(inputStream)
            ?: throw ImageDimensionException("Failed to create image input stream")
        
        val readers = ImageIO.getImageReaders(imageInputStream)
        if (!readers.hasNext()) {
            throw ImageDimensionException("No image reader found for image format")
        }
        
        val reader = readers.next()
        try {
            reader.input = imageInputStream
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            return width to height
        } finally {
            reader.dispose()
            imageInputStream.close()
        }
    }
    
    override fun scaleImageForGemini(imageBytes: ByteArray, maxDimension: Int): IImageDimensionService.ScaledImageResult {
        if (imageBytes.isEmpty()) {
            throw ImageDimensionException("Image bytes are empty")
        }
        
        // Read the original image
        val originalImage = ImageIO.read(ByteArrayInputStream(imageBytes))
            ?: throw ImageDimensionException("Failed to decode image for scaling")
        
        val originalWidth = originalImage.width
        val originalHeight = originalImage.height
        
        // Check if scaling is needed
        val maxCurrentDimension = maxOf(originalWidth, originalHeight)
        if (maxCurrentDimension <= maxDimension) {
            // No scaling needed - return original
            logger.debug("Image {}x{} is within limits, no scaling needed", originalWidth, originalHeight)
            return IImageDimensionService.ScaledImageResult(
                scaledBytes = imageBytes,
                scaledMimeType = detectMimeType(imageBytes),
                scaleFactor = 1.0,
                originalWidth = originalWidth,
                originalHeight = originalHeight,
                wasScaled = false
            )
        }
        
        // Calculate scale factor to fit within maxDimension
        val scaleFactor = maxDimension.toDouble() / maxCurrentDimension
        val newWidth = (originalWidth * scaleFactor).toInt().coerceAtLeast(1)
        val newHeight = (originalHeight * scaleFactor).toInt().coerceAtLeast(1)
        
        logger.debug(
            "Scaling image from {}x{} to {}x{} (scale factor: {:.3f})",
            originalWidth, originalHeight, newWidth, newHeight, scaleFactor
        )
        
        // Create scaled image with high-quality scaling
        val scaledImage = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB)
        val g2d = scaledImage.createGraphics()
        try {
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2d.drawImage(originalImage, 0, 0, newWidth, newHeight, null)
        } finally {
            g2d.dispose()
        }
        
        // Encode as JPEG for smaller file size (vision models don't need lossless)
        val outputStream = ByteArrayOutputStream()
        ImageIO.write(scaledImage, "jpeg", outputStream)
        val scaledBytes = outputStream.toByteArray()
        
        logger.debug(
            "Scaled image: {} bytes -> {} bytes ({:.1f}% of original)",
            imageBytes.size, scaledBytes.size, (scaledBytes.size.toDouble() / imageBytes.size) * 100
        )
        
        return IImageDimensionService.ScaledImageResult(
            scaledBytes = scaledBytes,
            scaledMimeType = "image/jpeg",
            scaleFactor = scaleFactor,
            originalWidth = originalWidth,
            originalHeight = originalHeight,
            wasScaled = true
        )
    }
    
    private fun detectMimeType(imageBytes: ByteArray): String {
        // Check magic bytes for common formats
        return when {
            imageBytes.size >= 8 && 
                imageBytes[0] == 0x89.toByte() && 
                imageBytes[1] == 0x50.toByte() && 
                imageBytes[2] == 0x4E.toByte() && 
                imageBytes[3] == 0x47.toByte() -> "image/png"
            imageBytes.size >= 2 && 
                imageBytes[0] == 0xFF.toByte() && 
                imageBytes[1] == 0xD8.toByte() -> "image/jpeg"
            imageBytes.size >= 12 && 
                imageBytes[0] == 0x52.toByte() && // R
                imageBytes[1] == 0x49.toByte() && // I
                imageBytes[2] == 0x46.toByte() && // F
                imageBytes[3] == 0x46.toByte() -> "image/webp"
            else -> "application/octet-stream"
        }
    }
}
