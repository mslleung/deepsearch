package io.deepsearch.application.services

import io.deepsearch.domain.agents.EntityExtractionInput
import io.deepsearch.domain.agents.IEntityExtractionAgent
import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.entities.IndexingTaskType
import io.deepsearch.domain.models.entities.MarkdownIndexingTask
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.repositories.EntityEmbeddings
import io.deepsearch.domain.repositories.IKnowledgeGraphRepository
import io.deepsearch.domain.repositories.IMarkdownIndexingTaskRepository
import io.deepsearch.domain.repositories.IWebpageMarkdownRepository
import io.deepsearch.domain.services.ITextEmbeddingService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * Interface for the markdown indexing worker.
 * 
 * The worker processes tasks from the markdown_indexing_tasks table,
 * generating embeddings and extracting knowledge graph data.
 */
interface IMarkdownIndexingWorker {
    /**
     * Notify the worker that new tasks are available.
     * This triggers workers to check for pending tasks.
     */
    fun notifyNewTasks()

    /**
     * Start the worker coroutines.
     * Should be called once during application startup.
     */
    fun start()

    /**
     * Stop all worker coroutines gracefully.
     * Should be called during application shutdown.
     */
    fun stop()
}

/**
 * Background worker that processes markdown indexing tasks.
 * 
 * Replaces the fire-and-forget pattern with a durable task queue.
 * Tasks survive server restarts and can be retried on failure.
 */
@OptIn(ExperimentalTime::class)
class MarkdownIndexingWorker(
    private val taskRepository: IMarkdownIndexingTaskRepository,
    private val textEmbeddingService: ITextEmbeddingService,
    private val entityExtractionAgent: IEntityExtractionAgent,
    private val knowledgeGraphRepository: IKnowledgeGraphRepository,
    private val webpageMarkdownRepository: IWebpageMarkdownRepository,
    private val tokenUsageService: ILlmTokenUsageService,
    private val applicationScope: IApplicationCoroutineScope,
    private val dispatchers: IDispatcherProvider
) : IMarkdownIndexingWorker {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        /** Number of parallel worker coroutines */
        private const val WORKER_CONCURRENCY = 4

        /** Timeout for waiting on notification channel (seconds) */
        private val POLL_TIMEOUT = 5.seconds
    }

    /** Channel for notifying workers of new tasks (CONFLATED = only latest matters) */
    private val notificationChannel = Channel<Unit>(Channel.CONFLATED)

    /** Flag to control worker lifecycle */
    @Volatile
    private var isRunning = true

    init {
        logger.info("Starting MarkdownIndexingWorker with {} workers", WORKER_CONCURRENCY)

        // Start worker coroutines for each task type
        repeat(WORKER_CONCURRENCY / 2) { workerId ->
            applicationScope.scope.launch(dispatchers.io) {
                workerLoop(workerId, IndexingTaskType.EMBEDDING)
            }
        }

        repeat(WORKER_CONCURRENCY / 2) { workerId ->
            applicationScope.scope.launch(dispatchers.io) {
                workerLoop(workerId + WORKER_CONCURRENCY / 2, IndexingTaskType.KNOWLEDGE_GRAPH)
            }
        }
    }

    override fun notifyNewTasks() {
        notificationChannel.trySend(Unit)
    }

    override fun start() {
        // No-op: workers start automatically in init block
    }

    override fun stop() {
        logger.info("Stopping MarkdownIndexingWorker")
        isRunning = false
        notificationChannel.close()
    }

    private suspend fun workerLoop(workerId: Int, taskType: IndexingTaskType) {
        logger.debug("Worker {} started for task type {}", workerId, taskType)

        while (isRunning) {
            try {
                // Try to claim a task
                val task = taskRepository.claimPendingTask(taskType)

                if (task != null) {
                    logger.debug(
                        "Worker {} claimed task {} (type: {}, url: {})",
                        workerId, task.id, task.taskType, task.url
                    )
                    processTask(task)
                } else {
                    // No tasks available, wait for notification or timeout
                    withTimeoutOrNull(POLL_TIMEOUT) {
                        notificationChannel.receive()
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    logger.error("Worker {} error: {}", workerId, e.message, e)
                    // Brief delay before retrying to avoid tight error loops
                    delay(1.seconds)
                }
            }
        }

        logger.debug("Worker {} stopped", workerId)
    }

    private suspend fun processTask(task: MarkdownIndexingTask) {
        val taskId = task.id ?: run {
            logger.error("Task has no ID: {}", task)
            return
        }

        try {
            when (task.taskType) {
                IndexingTaskType.EMBEDDING -> processEmbeddingTask(task)
                IndexingTaskType.KNOWLEDGE_GRAPH -> processKnowledgeGraphTask(task)
            }

            taskRepository.markCompleted(taskId)
            logger.debug("Task {} completed successfully", taskId)

        } catch (e: Exception) {
            logger.error("Task {} failed: {}", taskId, e.message, e)
            taskRepository.retryOrFail(taskId, e.message ?: "Unknown error")
        }
    }

    private suspend fun processEmbeddingTask(task: MarkdownIndexingTask) {
        val taskId = task.id!!

        if (task.markdown.isBlank()) {
            logger.debug("Task {} has empty markdown, skipping embedding", taskId)
            return
        }

        logger.debug("Generating embedding for task {} (url: {})", taskId, task.url)

        // Generate embedding
        val result = textEmbeddingService.embedDocuments(listOf(task.markdown))

        if (result.embeddings.isEmpty()) {
            throw RuntimeException("No embedding returned for URL: ${task.url}")
        }

        val embedding = result.embeddings[0]
        logger.debug(
            "Generated embedding with {} dimensions for task {} (used {} tokens)",
            embedding.size, taskId, result.tokenUsage.totalTokens
        )

        // Record token usage
        val sessionId = SessionId.fromStorageString(task.sessionId)
        tokenUsageService.recordTokenUsage(
            sessionId = sessionId,
            agentName = "MarkdownIndexingWorker.embedDocuments",
            modelName = result.tokenUsage.modelName,
            promptTokens = result.tokenUsage.promptTokens,
            outputTokens = result.tokenUsage.outputTokens,
            totalTokens = result.tokenUsage.totalTokens
        )

        // Store embedding
        val existing = webpageMarkdownRepository.findByUrl(task.url)
        if (existing != null) {
            webpageMarkdownRepository.upsert(
                existing.copy(
                    embedding = embedding,
                    updatedAt = Clock.System.now()
                )
            )
            logger.debug("Stored embedding for task {} (url: {})", taskId, task.url)
        } else {
            logger.warn("Webpage not found for task {} URL {} when storing embedding", taskId, task.url)
        }
    }

    private suspend fun processKnowledgeGraphTask(task: MarkdownIndexingTask) {
        val taskId = task.id!!

        logger.debug("Extracting entities for task {} (url: {})", taskId, task.url)

        // Extract entities and relationships
        val output = entityExtractionAgent.generate(
            EntityExtractionInput(markdown = task.markdown, sourceUrl = task.url)
        )

        // Record token usage
        val sessionId = SessionId.fromStorageString(task.sessionId)
        tokenUsageService.recordTokenUsage(
            sessionId = sessionId,
            agentName = "MarkdownIndexingWorker.entityExtraction",
            modelName = output.tokenUsage.modelName,
            promptTokens = output.tokenUsage.promptTokens,
            outputTokens = output.tokenUsage.outputTokens,
            totalTokens = output.tokenUsage.totalTokens
        )

        if (output.extraction.isEmpty()) {
            logger.debug("No entities extracted for task {} (url: {})", taskId, task.url)
            return
        }

        // Generate embeddings for entities
        val entityNames = output.extraction.entities.map { it.name }
        val embeddingResult = textEmbeddingService.embedForSimilarity(entityNames)
        val embeddings = EntityEmbeddings.fromMap(
            entityNames.zip(embeddingResult.embeddings).toMap()
        )

        // Record token usage for entity embeddings
        tokenUsageService.recordTokenUsage(
            sessionId = sessionId,
            agentName = "MarkdownIndexingWorker.embedForSimilarity",
            modelName = embeddingResult.tokenUsage.modelName,
            promptTokens = embeddingResult.tokenUsage.promptTokens,
            outputTokens = embeddingResult.tokenUsage.outputTokens,
            totalTokens = embeddingResult.tokenUsage.totalTokens
        )

        // Index into knowledge graph
        knowledgeGraphRepository.indexDocument(task.url, output.extraction, embeddings)

        logger.debug(
            "KG indexed {} entities and {} relationships for task {} (url: {})",
            output.extraction.entities.size,
            output.extraction.relationships.size,
            taskId,
            task.url
        )
    }
}

