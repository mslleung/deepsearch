package io.deepsearch.domain.ocr

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
     */
    suspend fun <T> use(block: (TessBaseAPI) -> T): T
}

/**
 * Implementation of ITesseractPool that manages a fixed pool of pre-warmed TessBaseAPI instances.
 * Uses a Channel-based pool for coroutine-friendly resource management.
 */
class TesseractPoolImpl() : ITesseractPool {
    companion object {
        /**
         * Tessdata path extracted from JAR resources to temporary directory.
         * Tesseract requires filesystem access to tessdata files, not JAR resources.
         */
        private val tessdataPath: String by lazy { extractTessdataToTempDirectory() }

        /**
         * Extracts tessdata files from JAR resources to a temporary directory.
         * Tesseract requires filesystem access to tessdata files, not JAR resources.
         */
        private fun extractTessdataToTempDirectory(): String {
            val tempDir = createTempDirectory("tessdata")
            tempDir.toFile().deleteOnExit()

            val tessdataFiles = listOf("eng.traineddata", "chi_sim.traineddata", "chi_tra.traineddata")

            for (fileName in tessdataFiles) {
                val resourcePath = "tessdata/$fileName"
                val resource = ITesseractPool::class.java.classLoader.getResourceAsStream(resourcePath)
                    ?: throw RuntimeException("Tessdata file not found: $resourcePath")

                val outputFile = tempDir / fileName
                outputFile.toFile().deleteOnExit()

                resource.use { input ->
                    outputFile.writeBytes(input.readBytes())
                }
            }

            return tempDir.toFile().absolutePath
        }

        /**
         * Creates a new TessBaseAPI instance initialized with English.
         */
        private fun createTessBaseAPI(): TessBaseAPI {
            return TessBaseAPI().apply {
                // Initialize with English, Chinese Simplified, and Chinese Traditional
                if (Init(tessdataPath, "eng") != 0) {   // prefer to use one language only for performance reasons
                    throw RuntimeException("Failed to initialize Tesseract OCR with tessdata path: $tessdataPath")
                }
            }
        }
    }

    private val processorCount = Runtime.getRuntime().availableProcessors()

    // Channel-based pool of pre-warmed TessBaseAPI instances
    private val pool = Channel<TessBaseAPI>(processorCount).apply {
        repeat(processorCount) {
            trySend(createTessBaseAPI())
        }
    }

    /**
     * Borrows a TessBaseAPI instance from the pool, executes the block, and returns it.
     * Suspends if all instances are currently in use until one becomes available.
     */
    override suspend fun <T> use(block: (TessBaseAPI) -> T): T {
        val api = pool.receive()
        try {
            return block(api)
        } finally {
            pool.send(api)
        }
    }
}

