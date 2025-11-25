package io.deepsearch.application.services

import io.deepsearch.domain.config.IApplicationCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class PeriodicIndexScheduler(
    private val periodicIndexService: IPeriodicIndexService,
    private val applicationScope: IApplicationCoroutineScope
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    init {
        startScheduler()
    }

    private fun startScheduler() {
        applicationScope.scope.launch {
            logger.info("Starting Periodic Index Scheduler")

            while (true) {
                try {
                    logger.info("Checking for due periodic index configs...")
                    periodicIndexService.checkAndRunDueConfigs()
                } catch (e: Exception) {
                    logger.error("Error in Periodic Index Scheduler: {}", e.message, e)
                }

                // Check every hour
                delay(1.hours)
            }
        }
    }
}

