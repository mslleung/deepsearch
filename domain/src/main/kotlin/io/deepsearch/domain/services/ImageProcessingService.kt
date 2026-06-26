package io.deepsearch.domain.services

import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.Loader
import org.bytedeco.opencv.global.opencv_core.CV_8UC1
import org.bytedeco.opencv.global.opencv_core.absdiff
import org.bytedeco.opencv.global.opencv_core.addWeighted
import org.bytedeco.opencv.global.opencv_core.countNonZero
import org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_COLOR
import org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_GRAYSCALE
import org.bytedeco.opencv.global.opencv_imgcodecs.IMWRITE_JPEG_QUALITY
import org.bytedeco.opencv.global.opencv_imgcodecs.imdecode
import org.bytedeco.opencv.global.opencv_imgcodecs.imencode
import org.bytedeco.opencv.global.opencv_imgproc.FILLED
import org.bytedeco.opencv.global.opencv_imgproc.FONT_HERSHEY_DUPLEX
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
    val elementIndex: Map<Int, AnnotatedElement>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AnnotatedScreenshot
        if (!imageBytes.contentEquals(other.imageBytes)) return false
        if (mimeType != other.mimeType) return false
        if (elementIndex != other.elementIndex) return false
        return true
    }

    override fun hashCode(): Int {
        var result = imageBytes.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + elementIndex.hashCode()
        return result
    }
}

data class AnnotatedElement(
    val tag: String,
    val text: String,
    val role: String?,
    val ariaLabel: String?,
    val centerX: Int,
    val centerY: Int,
    val index: Int
)

data class AnnotationTarget(
    val tag: String,
    val text: String,
    val role: String?,
    val ariaLabel: String?,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val centerX: Int,
    val centerY: Int,
    val index: Int
)

interface IImageProcessingService {
    fun getImageDimensions(imageBytes: ByteArray): Pair<Int, Int>
    fun downscaleToJpeg(imageBytes: ByteArray, maxHeight: Int, jpegQuality: Int): ByteArray
    fun cropToPng(imageBytes: ByteArray, x: Int, y: Int, width: Int, height: Int): ByteArray
    fun hasVisualChange(previous: ByteArray, current: ByteArray): Boolean
    fun annotate(
        screenshotBytes: ByteArray,
        elements: List<AnnotationTarget>
    ): AnnotatedScreenshot
    fun highlightRegion(imageBytes: ByteArray, centerX: Int, centerY: Int, margin: Int = 400): ByteArray
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

        private const val ANNOTATION_JPEG_QUALITY = 100
        private const val FONT_FACE = FONT_HERSHEY_DUPLEX
        private const val FONT_SCALE = 0.45
        private const val FONT_THICKNESS = 1
        private const val LABEL_PAD_X = 4
        private const val LABEL_PAD_Y = 4
        private const val BOX_OUTER_THICKNESS = 3
        private const val BOX_INNER_THICKNESS = 2

        private val SHADOW = Scalar(0.0, 0.0, 0.0, 0.0)
        private val BLACK = Scalar(0.0, 0.0, 0.0, 0.0)
        private val WHITE = Scalar(255.0, 255.0, 255.0, 0.0)

        private val PALETTE_BGR = arrayOf(
            Scalar(255.0, 230.0, 0.0, 0.0),   // cyan
            Scalar(147.0, 20.0, 255.0, 0.0),   // magenta
            Scalar(50.0, 255.0, 50.0, 0.0),    // green
        )
        private val PALETTE_TEXT = arrayOf(BLACK, WHITE, BLACK)

        private val HIGHLIGHT_COLOR = Scalar(0.0, 165.0, 255.0, 0.0) // orange BGR
        private const val HIGHLIGHT_ALPHA = 0.20
        private const val HIGHLIGHT_BORDER_THICKNESS = 4
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
        val clampedX = x.coerceIn(0, image.cols() - 1)
        val clampedY = y.coerceIn(0, image.rows() - 1)
        val clampedW = width.coerceIn(1, image.cols() - clampedX)
        val clampedH = height.coerceIn(1, image.rows() - clampedY)
        val roi = Mat(image, Rect(clampedX, clampedY, clampedW, clampedH))
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

    override fun annotate(
        screenshotBytes: ByteArray,
        elements: List<AnnotationTarget>
    ): AnnotatedScreenshot {
        val image = decodeMat(screenshotBytes, IMREAD_COLOR)
        val imgW = image.cols()
        val imgH = image.rows()

        val elementIndex = mutableMapOf<Int, AnnotatedElement>()
        val p1 = Point()
        val p2 = Point()
        val textOrigin = Point()
        val baselinePtr = IntPointer(1L).put(0)

        data class IntRect(val x: Int, val y: Int, val w: Int, val h: Int)

        fun overlaps(a: IntRect, b: IntRect): Boolean =
            a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y

        val occupiedRects = mutableListOf<IntRect>()

        for (element in elements) {
            val boxX = element.left.toInt().coerceIn(0, imgW - 1)
            val boxY = element.top.toInt().coerceIn(0, imgH - 1)
            val boxR = element.right.toInt().coerceIn((boxX + 4).coerceAtMost(imgW), imgW)
            val boxB = element.bottom.toInt().coerceIn((boxY + 4).coerceAtMost(imgH), imgH)
            occupiedRects.add(IntRect(boxX, boxY, boxR - boxX, boxB - boxY))
        }

        for (element in elements) {
            val labelNumber = element.index
            elementIndex[labelNumber] = AnnotatedElement(
                tag = element.tag,
                text = element.text,
                role = element.role,
                ariaLabel = element.ariaLabel,
                centerX = element.centerX,
                centerY = element.centerY,
                index = element.index
            )

            val boxX = element.left.toInt().coerceIn(0, imgW - 1)
            val boxY = element.top.toInt().coerceIn(0, imgH - 1)
            val boxR = element.right.toInt().coerceIn((boxX + 4).coerceAtMost(imgW), imgW)
            val boxB = element.bottom.toInt().coerceIn((boxY + 4).coerceAtMost(imgH), imgH)

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

            val candidates = listOf(
                boxX to (boxY - badgeH - 1),                   // above-left
                (boxR - badgeW) to (boxY - badgeH - 1),        // above-right
                boxX to (boxB + 1),                             // below-left
                (boxR - badgeW) to (boxB + 1),                  // below-right
                (boxX + 1) to (boxY + 1)                        // inside top-left (last resort)
            )

            var badgeX = boxX.coerceIn(0, (imgW - badgeW).coerceAtLeast(0))
            var badgeY = (boxY - badgeH - 1).coerceIn(0, (imgH - badgeH).coerceAtLeast(0))

            for ((cx, cy) in candidates) {
                val clampedX = cx.coerceIn(0, (imgW - badgeW).coerceAtLeast(0))
                val clampedY = cy.coerceIn(0, (imgH - badgeH).coerceAtLeast(0))
                val candidate = IntRect(clampedX, clampedY, badgeW, badgeH)
                if (occupiedRects.none { overlaps(candidate, it) }) {
                    badgeX = clampedX
                    badgeY = clampedY
                    break
                }
            }

            occupiedRects.add(IntRect(badgeX, badgeY, badgeW, badgeH))

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

        val outputBytes = encodeJpeg(image, ANNOTATION_JPEG_QUALITY)
        image.close()

        return AnnotatedScreenshot(
            imageBytes = outputBytes,
            mimeType = "image/jpeg",
            elementIndex = elementIndex
        )
    }

    override fun highlightRegion(imageBytes: ByteArray, centerX: Int, centerY: Int, margin: Int): ByteArray {
        val image = decodeMat(imageBytes, IMREAD_COLOR)
        val imgW = image.cols()
        val imgH = image.rows()

        val x1 = (centerX - margin).coerceIn(0, imgW - 1)
        val y1 = (centerY - margin).coerceIn(0, imgH - 1)
        val x2 = (centerX + margin).coerceIn(x1 + 1, imgW)
        val y2 = (centerY + margin).coerceIn(y1 + 1, imgH)

        val roi = Mat(image, Rect(x1, y1, x2 - x1, y2 - y1))
        val overlay = roi.clone()
        rectangle(overlay, Point(0, 0), Point(overlay.cols(), overlay.rows()),
            HIGHLIGHT_COLOR, FILLED, LINE_8, 0)
        addWeighted(overlay, HIGHLIGHT_ALPHA, roi, 1.0 - HIGHLIGHT_ALPHA, 0.0, roi)
        overlay.close()

        rectangle(image, Point(x1, y1), Point(x2, y2),
            HIGHLIGHT_COLOR, HIGHLIGHT_BORDER_THICKNESS, LINE_8, 0)

        val result = encodeJpeg(image, ANNOTATION_JPEG_QUALITY)
        roi.close()
        image.close()
        return result
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
