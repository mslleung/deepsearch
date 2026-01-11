package io.deepsearch.application.services.batch

import io.deepsearch.application.services.IWebpageCacheService
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.entities.BatchUrlState
import io.deepsearch.domain.models.valueobjects.PeriodicIndexSessionId
import io.deepsearch.domain.repositories.IBatchUrlStateRepository
import io.deepsearch.domain.services.IGeminiFileSearchService
import io.deepsearch.domain.services.ITemporaryFileStorageService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Background worker that uploads files to Gemini File Search independently of the main pipeline.
 * 
 * Gemini File Search is a complete RAG solution:
 * - Automatically indexes and embeds uploaded files
 * - Retrieves relevant chunks at query time via the FileSearch tool
 * - No need for us to generate embeddings or maintain our own index
 * 
 * See: https://ai.google.dev/gemini-api/docs/file-search
 * 
 * Flow: PENDING_FILE_UPLOAD → FILE_UPLOADED (terminal state for files)
 * 
 * File bytes are stored in GCS (not database) for:
 * - Server restart survival
 * - Avoiding database bloat
 * - Cost-effective storage (GCS free tier: 5GB)
 * 
 * Features:
 * - Concurrent uploads with rate limiting
 * - Automatic retry with exponential backoff on rate limit errors
 * - Deduplication via file hash (skips already uploaded files)
 * - Complete independence from the HTML pipeline stages
 * - Graceful shutdown when batch job completes or is cancelled
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FileUploadBackgroundWorker(
    private val batchUrlStateRepository: IBatchUrlStateRepository,
    private val geminiFileSearchService: IGeminiFileSearchService,
    private val temporaryFileStorage: ITemporaryFileStorageService,
    private val webpageCacheService: IWebpageCacheService,
    private val dispatchers: IDispatcherProvider
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    
    companion object {
        /** Maximum concurrent file uploads (conservative for rate limits) */
        private const val UPLOAD_CONCURRENCY = 3
        
        /** How often to check for new pending file uploads */
        private const val POLL_INTERVAL_MS = 5000L
        
        /** Base delay for retry on rate limit errors */
        private const val RETRY_BASE_DELAY_MS = 5000L
        
        /** Maximum retry attempts per file */
        private const val MAX_RETRIES = 3
    }

    /**
     * Start background file upload processing for a batch job.
     * 
     * @param jobId The batch job ID to process files for
     * @param scope The coroutine scope to run in (typically the pipeline's scope)
     * @return A Job that can be cancelled when the batch job stops
     */
    fun start(jobId: Long, scope: CoroutineScope): Job {
        return scope.launch(dispatchers.io) {
            logger.info("[{}] File upload background worker started", jobId)
            
            while (isActive) {
                try {
                    val pendingFiles = batchUrlStateRepository.findPendingFileUploads(jobId)
                    
                    if (pendingFiles.isEmpty()) {
                        // No files to process, wait and check again
                        delay(POLL_INTERVAL_MS)
                        continue
                    }
                    
                    logger.info("[{}] Processing {} pending file uploads", jobId, pendingFiles.size)
                    
                    // Process files with concurrency limit
                    pendingFiles.asFlow()
                        .flatMapMerge(concurrency = UPLOAD_CONCURRENCY) { urlState ->
                            flow {
                                try {
                                    uploadFileWithRetry(jobId, urlState)
                                    emit(urlState)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    logger.warn("[{}] Failed to upload file {}: {}", 
                                        jobId, urlState.url, e.message)
                                    urlState.markFailed(e.message ?: "Upload failed")
                                    batchUrlStateRepository.update(urlState)
                                }
                            }
                        }
                        .collect { /* processed */ }
                        
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("[{}] Error in file upload worker: {}", jobId, e.message, e)
                    delay(POLL_INTERVAL_MS) // Wait before retrying
                }
            }
            
            logger.info("[{}] File upload background worker stopped", jobId)
        }
    }
    
    /**
     * Upload a file with retry logic for rate limit errors.
     * 
     * Handles server restart scenarios:
     * - If file already in Gemini (hash match) → skip upload
     * - If GCS file missing but file in Gemini → recover gracefully
     * - If GCS file missing and not in Gemini → fail (re-crawl needed)
     */
    private suspend fun uploadFileWithRetry(jobId: Long, urlState: BatchUrlState) {
        val domain = extractDomain(urlState.url)
        val storeInfo = geminiFileSearchService.getOrCreateStore(domain)
        
        // Check if file already exists by hash (deduplication + crash recovery)
        val existingFile = urlState.fileHash?.let { hash ->
            geminiFileSearchService.findFileByHash(storeInfo.name, hash)
        }
        
        if (existingFile != null) {
            logger.debug("[{}] File already uploaded (hash match): {}", jobId, urlState.url)
            // Delete from GCS since we don't need it anymore (may already be deleted)
            deleteFromGcs(urlState)
            urlState.markFileUploaded(existingFile.name)
            batchUrlStateRepository.update(urlState)
            // Cache file info for future deletion
            cacheFileInfo(jobId, urlState, existingFile.name)
            return
        }
        
        // Need to upload - get file bytes from GCS
        val storagePath = urlState.fileStoragePath
            ?: throw IllegalStateException("No storage path for URL: ${urlState.url}")
        
        val fileBytes = temporaryFileStorage.retrieve(storagePath)
        
        // Handle edge case: GCS file missing (possibly deleted after crash)
        if (fileBytes == null) {
            // Double-check Gemini one more time - maybe we uploaded before crash
            // but the initial findFileByHash failed due to API error
            val recoveredFile = urlState.fileHash?.let { hash ->
                try {
                    geminiFileSearchService.findFileByHash(storeInfo.name, hash)
                } catch (e: Exception) {
                    logger.warn("[{}] Failed to check Gemini for recovery: {}", jobId, e.message)
                    null
                }
            }
            
            if (recoveredFile != null) {
                logger.info("[{}] Recovered from crash - file was already in Gemini: {}", jobId, urlState.url)
                urlState.markFileUploaded(recoveredFile.name)
                batchUrlStateRepository.update(urlState)
                // Cache file info for future deletion
                cacheFileInfo(jobId, urlState, recoveredFile.name)
                return
            }
            
            // File truly missing - needs re-crawl
            throw IllegalStateException(
                "File not found in GCS ($storagePath) and not in Gemini. " +
                "URL may need to be re-crawled: ${urlState.url}"
            )
        }
        
        var lastException: Exception? = null
        
        repeat(MAX_RETRIES) { attempt ->
            try {
                val fileInfo = geminiFileSearchService.uploadFile(
                    storeName = storeInfo.name,
                    fileBytes = fileBytes,
                    mimeType = urlState.fileMimeType ?: "application/octet-stream",
                    sourceUrl = urlState.url,
                    fileHash = urlState.fileHash ?: ""
                )
                
                logger.info("[{}] File uploaded: {} → {}", jobId, urlState.url, fileInfo.name)
                
                // Delete from GCS after successful upload to Gemini
                deleteFromGcs(urlState)
                
                urlState.markFileUploaded(fileInfo.name)
                batchUrlStateRepository.update(urlState)
                // Cache file info for future deletion
                cacheFileInfo(jobId, urlState, fileInfo.name)
                return
                
            } catch (e: Exception) {
                lastException = e
                
                // Check if this is a rate limit error
                if (isRateLimitError(e)) {
                    val delayMs = RETRY_BASE_DELAY_MS * (1 shl attempt) // Exponential backoff
                    logger.warn("[{}] Rate limited uploading {}, retrying in {}ms (attempt {})", 
                        jobId, urlState.url, delayMs, attempt + 1)
                    delay(delayMs)
                } else {
                    // Non-rate-limit error, don't retry
                    throw e
                }
            }
        }
        
        throw lastException ?: RuntimeException("Upload failed after $MAX_RETRIES attempts")
    }
    
    /**
     * Delete file from GCS temporary storage.
     */
    private suspend fun deleteFromGcs(urlState: BatchUrlState) {
        val storagePath = urlState.fileStoragePath ?: return
        try {
            temporaryFileStorage.delete(storagePath)
            logger.debug("Deleted temporary file from GCS: {}", storagePath)
        } catch (e: Exception) {
            logger.warn("Failed to delete temporary file from GCS {}: {}", storagePath, e.message)
        }
    }
    
    /**
     * Cache file info in WebpageMarkdown for future deletion capability.
     * 
     * This stores the Gemini document name so we can delete the file later
     * without needing to scan the entire Gemini store.
     */
    private suspend fun cacheFileInfo(jobId: Long, urlState: BatchUrlState, documentName: String) {
        try {
            val sessionId = PeriodicIndexSessionId(jobId)
            webpageCacheService.cacheWebpageBatch(
                url = urlState.url,
                title = urlState.title,
                description = null, // Files don't have descriptions
                markdown = null, // File content is in Gemini, not markdown
                html = null,
                httpStatus = 200,
                httpReason = "OK",
                mimeType = urlState.fileMimeType,
                sessionId = sessionId,
                fileSearchDocumentName = documentName
            )
            logger.debug("[{}] Cached file info for future deletion: {} → {}", jobId, urlState.url, documentName)
        } catch (e: Exception) {
            // Don't fail the upload if caching fails - the file is still in Gemini
            logger.warn("[{}] Failed to cache file info for {}: {}", jobId, urlState.url, e.message)
        }
    }
    
    /**
     * Check if an exception indicates a rate limit error.
     */
    private fun isRateLimitError(e: Exception): Boolean {
        val message = e.message?.lowercase() ?: return false
        return message.contains("429") || 
               message.contains("rate") || 
               message.contains("resource_exhausted") ||
               message.contains("quota")
    }
    
    /**
     * Extract domain from URL for file search store lookup.
     */
    private fun extractDomain(url: String): String {
        return try {
            URI(url).host?.lowercase() ?: url
        } catch (e: Exception) {
            url
        }
    }
}
