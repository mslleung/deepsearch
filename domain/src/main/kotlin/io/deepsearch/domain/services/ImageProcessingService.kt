package io.deepsearch.domain.services

import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.Loader
import org.bytedeco.opencv.global.opencv_core.CV_8UC1
import org.bytedeco.opencv.global.opencv_core.absdiff
import org.bytedeco.opencv.global.opencv_core.countNonZero
import org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_COLOR
import org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_GRAYSCALE
import org.bytedeco.opencv.global.opencv_imgcodecs.IMWRITE_JPEG_QUALITY
import org.bytedeco.opencv.global.opencv_imgcodecs.imdecode
import org.bytedeco.opencv.global.opencv_imgcodecs.imencode
import org.bytedeco.opencv.global.opencv_imgproc.THRESH_BINARY
import org.bytedeco.opencv.global.opencv_imgproc.resize
import org.bytedeco.opencv.global.opencv_imgproc.threshold
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Rect
import org.bytedeco.opencv.opencv_core.Size
import org.slf4j.LoggerFactory

interface IImageProcessingService {
    fun getImageDimensions(imageBytes: ByteArray): Pair<Int, Int>
    fun downscaleToJpeg(imageBytes: ByteArray, maxHeight: Int, jpegQuality: Int): ByteArray
    fun cropToPng(imageBytes: ByteArray, x: Int, y: Int, width: Int, height: Int): ByteArray
    fun hasVisualChange(previous: ByteArray, current: ByteArray): Boolean
}

/**
 * OpenCV-based implementation of [IImageProcessingService].
 */
class ImageProcessingService : IImageProcessingService {

    companion object {
        private val logger = LoggerFactory.getLogger(ImageProcessingService::class.java)

        init {
            Loader.load(org.bytedeco.opencv.global.opencv_imgcodecs::class.java)
        }

        private const val PIXEL_DIFF_THRESHOLD = 30.0
    }

    override fun getImageDimensions(imageBytes: ByteArray): Pair<Int, Int> {
        val mat = decodeMat(imageBytes, IMREAD_COLOR)
        val dims = Pair(mat.cols(), mat.rows())
        mat.close()
        return dims
    }

    override fun downscaleToJpeg(imageBytes: ByteArray, maxHeight: Int, jpegQuality: Int): ByteArray {
        val image = decodeMat(imageBytes, IMREAD_COLOR)
        if (image.rows() <= maxHeight) {
            val result = encodeJpeg(image, jpegQuality)
            image.close()
            return result
        }

        val scale = maxHeight.toDouble() / image.rows()
        val newWidth = (image.cols() * scale).toInt().coerceAtLeast(1)
        val resized = Mat()
        resize(image, resized, Size(newWidth, maxHeight))
        image.close()

        val result = encodeJpeg(resized, jpegQuality)
        resized.close()
        return result
    }

    override fun cropToPng(imageBytes: ByteArray, x: Int, y: Int, width: Int, height: Int): ByteArray {
        val image = decodeMat(imageBytes, IMREAD_COLOR)
        val roi = Mat(image, Rect(x, y, width, height))
        val result = encodePng(roi)
        roi.close()
        image.close()
        return result
    }

    override fun hasVisualChange(previous: ByteArray, current: ByteArray): Boolean {
        val prevMat = decodeMat(previous, IMREAD_GRAYSCALE)
        val curMat = decodeMat(current, IMREAD_GRAYSCALE)

        if (prevMat.rows() != curMat.rows() || prevMat.cols() != curMat.cols()) {
            logger.debug(
                "Visual diff: size mismatch prev={}x{} cur={}x{} → changed",
                prevMat.cols(), prevMat.rows(), curMat.cols(), curMat.rows()
            )
            prevMat.close(); curMat.close()
            return true
        }

        val diff = Mat()
        absdiff(prevMat, curMat, diff)
        threshold(diff, diff, PIXEL_DIFF_THRESHOLD, 255.0, THRESH_BINARY)

        val changedPixels = countNonZero(diff)
        val totalPixels = diff.rows() * diff.cols()

        logger.debug(
            "Visual diff: {}x{}, changedPixels={}/{} → {}",
            prevMat.cols(), prevMat.rows(),
            changedPixels, totalPixels,
            if (changedPixels > 0) "CHANGED" else "unchanged"
        )

        prevMat.close(); curMat.close(); diff.close()

        return changedPixels > 0
    }

    private fun decodeMat(bytes: ByteArray, flags: Int): Mat {
        val bp = BytePointer(bytes.size.toLong())
        bp.put(bytes, 0, bytes.size)
        val buf = Mat(1, bytes.size, CV_8UC1, bp)
        val image = imdecode(buf, flags)
        buf.close()
        bp.close()
        if (image == null || image.empty()) {
            throw IllegalArgumentException("Failed to decode screenshot image (${bytes.size} bytes)")
        }
        return image
    }

    private fun encodeJpeg(image: Mat, quality: Int): ByteArray {
        val buf = BytePointer()
        val params = IntPointer(IMWRITE_JPEG_QUALITY, quality)
        imencode(BytePointer(".jpg"), image, buf, params)
        val size = buf.limit().toInt()
        val outputBytes = ByteArray(size)
        buf.get(outputBytes, 0, size)
        buf.close()
        params.close()
        return outputBytes
    }

    private fun encodePng(image: Mat): ByteArray {
        val buf = BytePointer()
        imencode(BytePointer(".png"), image, buf)
        val size = buf.limit().toInt()
        val outputBytes = ByteArray(size)
        buf.get(outputBytes, 0, size)
        buf.close()
        return outputBytes
    }
}
