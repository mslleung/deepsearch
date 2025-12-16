package io.deepsearch.domain.services

import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.OcrLanguage
import io.deepsearch.domain.ocr.ITesseractPool
import kotlinx.coroutines.withContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.leptonica.global.leptonica
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Service for extracting text from images using Tesseract OCR.
 */
interface IOcrImageTextExtractionService {
    /**
     * Extracts text from an image using OCR.
     * @param imageBytes Raw image bytes
     * @param mimeType Image MIME type
     * @param language OCR language to use for text extraction
     * @return Extracted text if text is detected and has meaningful content (>2 chars trimmed), null otherwise
     */
    suspend fun extractText(imageBytes: ByteArray, mimeType: ImageMimeType, language: OcrLanguage = OcrLanguage.DEFAULT): String
}

/**
 * Implementation of IOcrImageTextExtractionService using Tesseract OCR via TesseractPool.
 */
class OcrImageTextExtractionService(
    private val tesseractPool: ITesseractPool,
    private val dispatcherProvider: IDispatcherProvider
) : IOcrImageTextExtractionService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun extractText(imageBytes: ByteArray, mimeType: ImageMimeType, language: OcrLanguage): String {
        return withContext(dispatcherProvider.default) {
            val imagePointer = BytePointer(*imageBytes)
            val pix = leptonica.pixReadMem(imagePointer, imageBytes.size.toLong())
            if (pix == null || pix.isNull) {
                throw Error("Failed to pixReadMem")
            }

            try {
                tesseractPool.use(language) { api ->
                    api.SetImage(pix)
                    val text = api.GetUTF8Text()?.string?.trim()
                    if (text != null && text.isNotBlank()) {
                        if (text.length > 2) {
                            logger.debug("OCR detected text (language={}): {}", language.code, text)
                            text
                        } else {
                            logger.debug("OCR detected text: {}, Skipping as there are less than 3 characters", text)
                            ""
                        }
                    } else {
                        ""
                    }
                }
            } finally {
                leptonica.pixDestroy(pix)
            }
        }
    }
}

