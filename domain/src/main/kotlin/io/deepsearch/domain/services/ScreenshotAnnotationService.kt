package io.deepsearch.domain.services

import io.deepsearch.domain.browser.IBrowserPage
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
import org.bytedeco.opencv.global.opencv_imgproc.FILLED
import org.bytedeco.opencv.global.opencv_imgproc.FONT_HERSHEY_DUPLEX
import org.bytedeco.opencv.global.opencv_imgproc.INTER_AREA
import org.bytedeco.opencv.global.opencv_imgproc.LINE_8
import org.bytedeco.opencv.global.opencv_imgproc.LINE_AA
import org.bytedeco.opencv.global.opencv_imgproc.THRESH_BINARY
import org.bytedeco.opencv.global.opencv_imgproc.getTextSize
import org.bytedeco.opencv.global.opencv_imgproc.putText
import org.bytedeco.opencv.global.opencv_imgproc.rectangle
import org.bytedeco.opencv.global.opencv_imgproc.resize
import org.bytedeco.opencv.global.opencv_imgproc.threshold
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Point
import org.bytedeco.opencv.opencv_core.Rect
import org.bytedeco.opencv.opencv_core.Scalar
import org.bytedeco.opencv.opencv_core.Size
import org.slf4j.LoggerFactory

data class AnnotatedScreenshot(
    val imageBytes: ByteArray,
    val mimeType: String,
    val elementIndex: Map<Int, IBrowserPage.InteractiveElementInfo>
)

/**
 * Overlays numbered labels on a screenshot at the bounding box locations
 * of interactive elements using OpenCV native drawing. The VLM sees these
 * labels and can reference elements by number, while clicks are dispatched
 * by coordinates.
 */
class ScreenshotAnnotationService {

    companion object {
        private val logger = LoggerFactory.getLogger(ScreenshotAnnotationService::class.java)

        init {
            Loader.load(org.bytedeco.opencv.global.opencv_imgcodecs::class.java)
        }

        private const val JPEG_QUALITY = 90

        private const val FONT_FACE = FONT_HERSHEY_DUPLEX
        private const val FONT_SCALE = 0.45
        private const val FONT_THICKNESS = 1
        private const val LABEL_PAD_X = 4
        private const val LABEL_PAD_Y = 4

        private const val BOX_OUTER_THICKNESS = 3
        private const val BOX_INNER_THICKNESS = 2

        private const val PIXEL_DIFF_THRESHOLD = 30.0

        private val SHADOW = Scalar(0.0, 0.0, 0.0, 0.0)
        private val WHITE = Scalar(255.0, 255.0, 255.0, 0.0)
        private val BLACK = Scalar(0.0, 0.0, 0.0, 0.0)

        private val PALETTE_BGR = arrayOf(
            Scalar(255.0, 230.0, 0.0, 0.0),
            Scalar(147.0, 20.0, 255.0, 0.0),
            Scalar(50.0, 255.0, 50.0, 0.0),
        )
        private val PALETTE_TEXT = arrayOf(BLACK, WHITE, BLACK)
    }

    fun annotate(
        screenshotBytes: ByteArray,
        elements: List<IBrowserPage.InteractiveElementInfo>
    ): AnnotatedScreenshot {
        val t0 = System.nanoTime()

        val image = decodeMat(screenshotBytes, IMREAD_COLOR)

        val t1 = System.nanoTime()

        val imgW = image.cols()
        val imgH = image.rows()

        val elementIndex = mutableMapOf<Int, IBrowserPage.InteractiveElementInfo>()
        val p1 = Point()
        val p2 = Point()
        val textOrigin = Point()
        val baselinePtr = IntPointer(1L).put(0)

        for (element in elements) {
            val labelNumber = element.index
            elementIndex[labelNumber] = element
            val bb = element.boundingBox

            val boxX = bb.left.toInt().coerceIn(0, imgW - 1)
            val boxY = bb.top.toInt().coerceIn(0, imgH - 1)
            val boxR = bb.right.toInt().coerceIn((boxX + 4).coerceAtMost(imgW), imgW)
            val boxB = bb.bottom.toInt().coerceIn((boxY + 4).coerceAtMost(imgH), imgH)

            val paletteIdx = labelNumber % PALETTE_BGR.size
            val accentColor = PALETTE_BGR[paletteIdx]

            p1.x(boxX).y(boxY)
            p2.x(boxR).y(boxB)
            rectangle(image, p1, p2, SHADOW, BOX_OUTER_THICKNESS, LINE_8, 0)
            rectangle(image, p1, p2, accentColor, BOX_INNER_THICKNESS, LINE_8, 0)

            val labelText = labelNumber.toString()
            baselinePtr.put(0L, 0)
            val textSize = getTextSize(labelText, FONT_FACE, FONT_SCALE, FONT_THICKNESS, baselinePtr)
            val textW = textSize.width()
            val textH = textSize.height()

            val badgeW = textW + LABEL_PAD_X * 2
            val badgeH = textH + LABEL_PAD_Y * 2
            val badgeX = boxX.coerceIn(0, (imgW - badgeW).coerceAtLeast(0))
            val badgeY = (boxY - badgeH - 1).coerceIn(0, (imgH - badgeH).coerceAtLeast(0))

            p1.x(badgeX - 1).y(badgeY - 1)
            p2.x(badgeX + badgeW + 1).y(badgeY + badgeH + 1)
            rectangle(image, p1, p2, SHADOW, FILLED, LINE_8, 0)

            p1.x(badgeX).y(badgeY)
            p2.x(badgeX + badgeW).y(badgeY + badgeH)
            rectangle(image, p1, p2, accentColor, FILLED, LINE_8, 0)

            textOrigin.x(badgeX + LABEL_PAD_X).y(badgeY + LABEL_PAD_Y + textH)
            putText(image, labelText, textOrigin, FONT_FACE, FONT_SCALE, PALETTE_TEXT[paletteIdx], FONT_THICKNESS, LINE_AA, false)
        }

        p1.close()
        p2.close()
        textOrigin.close()
        baselinePtr.close()

        val t2 = System.nanoTime()

        val outputBytes = encodeJpeg(image, JPEG_QUALITY)
        image.close()

        val t3 = System.nanoTime()

        logger.debug(
            "Annotation timing: decode={}ms, draw({}el)={}ms, encode={}ms, total={}ms",
            (t1 - t0) / 1_000_000, elements.size,
            (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000, (t3 - t0) / 1_000_000
        )

        return AnnotatedScreenshot(
            imageBytes = outputBytes,
            mimeType = "image/jpeg",
            elementIndex = elementIndex
        )
    }

    fun getImageDimensions(imageBytes: ByteArray): Pair<Int, Int> {
        val mat = decodeMat(imageBytes, IMREAD_COLOR)
        val dims = Pair(mat.cols(), mat.rows())
        mat.close()
        return dims
    }

    fun downscaleToJpeg(imageBytes: ByteArray, maxHeight: Int, jpegQuality: Int): ByteArray {
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

    fun cropToPng(imageBytes: ByteArray, x: Int, y: Int, width: Int, height: Int): ByteArray {
        val image = decodeMat(imageBytes, IMREAD_COLOR)
        val roi = Mat(image, Rect(x, y, width, height))
        val result = encodePng(roi)
        roi.close()
        image.close()
        return result
    }

    fun hasVisualChange(previous: ByteArray, current: ByteArray): Boolean {
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
