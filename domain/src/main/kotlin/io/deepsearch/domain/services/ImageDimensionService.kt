package io.deepsearch.domain.services

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Service for extracting image dimensions from byte arrays.
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
}

class ImageDimensionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class ImageDimensionService : IImageDimensionService {
    
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
}
