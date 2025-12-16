package io.deepsearch.domain.ocr

import io.deepsearch.domain.models.valueobjects.OcrLanguage
import kotlinx.coroutines.channels.Channel
import org.bytedeco.tesseract.TessBaseAPI
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.writeBytes

/**
 * Pool interface for managing TessBaseAPI instances for parallel OCR processing.
 */
interface ITesseractPool {
    /**
     * Borrows a TessBaseAPI instance from the pool, executes the block, and returns it.
     * Suspends if all instances are currently in use.
     * 
     * @param language The OCR language to use for text extraction
     * @param block The operation to perform with the TessBaseAPI instance
     */
    suspend fun <T> use(language: OcrLanguage = OcrLanguage.DEFAULT, block: (TessBaseAPI) -> T): T
}

/**
 * A pooled TessBaseAPI instance that tracks its currently initialized language.
 */
private class PooledTessBaseAPI(
    val api: TessBaseAPI,
    var currentLanguage: OcrLanguage
)

/**
 * Implementation of ITesseractPool that manages a fixed pool of TessBaseAPI instances.
 * Uses a Channel-based pool for coroutine-friendly resource management.
 * 
 * Language switching is handled by reinitializing the TessBaseAPI when a different
 * language is requested than what the instance is currently configured for.
 */
class TesseractPoolImpl() : ITesseractPool {
    companion object {
        /**
         * Tessdata path extracted from JAR resources to temporary directory.
         * Tesseract requires filesystem access to tessdata files, not JAR resources.
         */
        private val tessdataPath: String by lazy { extractTessdataToTempDirectory() }

        /**
         * List of all tessdata files to extract (one per supported language).
         */
        private val tessdataFiles: List<String> = OcrLanguage.entries.map { "${it.code}.traineddata" }

        /**
         * Extracts tessdata files from JAR resources to a temporary directory.
         * Tesseract requires filesystem access to tessdata files, not JAR resources.
         */
        private fun extractTessdataToTempDirectory(): String {
            val tempDir = createTempDirectory("tessdata")
            tempDir.toFile().deleteOnExit()

            for (fileName in tessdataFiles) {
                val resourcePath = "tessdata/$fileName"
                val resource = ITesseractPool::class.java.classLoader.getResourceAsStream(resourcePath)
                    ?: throw RuntimeException("Tessdata file not found: $resourcePath. Please ensure all language traineddata files are present in resources/tessdata/")

                val outputFile = tempDir / fileName
                outputFile.toFile().deleteOnExit()

                resource.use { input ->
                    outputFile.writeBytes(input.readBytes())
                }
            }

            return tempDir.toFile().absolutePath
        }

        /**
         * Creates a new TessBaseAPI instance initialized with the default language.
         */
        private fun createPooledTessBaseAPI(): PooledTessBaseAPI {
            val api = TessBaseAPI()
            val defaultLanguage = OcrLanguage.DEFAULT
            if (api.Init(tessdataPath, defaultLanguage.code) != 0) {
                throw RuntimeException("Failed to initialize Tesseract OCR with language: ${defaultLanguage.code}")
            }
            return PooledTessBaseAPI(api, defaultLanguage)
        }

        /**
         * Reinitializes a TessBaseAPI instance with a new language if needed.
         */
        private fun ensureLanguage(pooled: PooledTessBaseAPI, language: OcrLanguage) {
            if (pooled.currentLanguage != language) {
                // Reinitialize with the new language
                if (pooled.api.Init(tessdataPath, language.code) != 0) {
                    throw RuntimeException("Failed to reinitialize Tesseract OCR with language: ${language.code}")
                }
                pooled.currentLanguage = language
            }
        }
    }

    private val processorCount = Runtime.getRuntime().availableProcessors()

    // Channel-based pool of TessBaseAPI instances
    private val pool = Channel<PooledTessBaseAPI>(processorCount).apply {
        repeat(processorCount) {
            trySend(createPooledTessBaseAPI())
        }
    }

    /**
     * Borrows a TessBaseAPI instance from the pool, ensures it's configured for the
     * requested language, executes the block, and returns the instance to the pool.
     * Suspends if all instances are currently in use until one becomes available.
     */
    override suspend fun <T> use(language: OcrLanguage, block: (TessBaseAPI) -> T): T {
        val pooled = pool.receive()
        try {
            ensureLanguage(pooled, language)
            return block(pooled.api)
        } finally {
            pool.send(pooled)
        }
    }
}
