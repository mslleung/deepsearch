package io.deepsearch.presentation.admin.controllers

import io.deepsearch.application.services.IUsageService
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.repositories.IApiKeyRepository
import io.deepsearch.domain.repositories.IQuerySessionRepository
import io.deepsearch.domain.repositories.IUserRepository
import io.deepsearch.domain.repositories.IUserSubscriptionRepository
import io.deepsearch.presentation.admin.dto.AdminUsageStatsDto
import io.deepsearch.presentation.admin.dto.AdminUserUsageDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import kotlinx.datetime.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.number
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class AdminUsageController(
    private val userRepository: IUserRepository,
    private val apiKeyRepository: IApiKeyRepository,
    private val querySessionRepository: IQuerySessionRepository,
    private val userSubscriptionRepository: IUserSubscriptionRepository,
    private val usageService: IUsageService
) {

    suspend fun getAggregateUsageStats(call: ApplicationCall) {
        val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 30
        
        val users = userRepository.findAll()
        val totalUsers = users.size
        
        // Count API keys
        var totalApiKeys = 0
        for (user in users) {
            val keys = apiKeyRepository.findByUserId(user.id!!)
            totalApiKeys += keys.size
        }
        
        // Get usage stats
        val now = Clock.System.now()
        val startDate = now - days.days
        val sessions = querySessionRepository.findByUserIdAndDateRange(UserId(0), startDate, now) // Get all sessions
        
        val dailyUsage = sessions.groupBy { session ->
            // Format as YYYY-MM-DD from epoch millis
            val instant = Instant.fromEpochMilliseconds(session.createdAt.toEpochMilliseconds())
            val localDateTime = instant.toLocalDateTime(kotlinx.datetime.TimeZone.UTC)
            "${localDateTime.year}-${localDateTime.month.number.toString().padStart(2, '0')}-${localDateTime.day.toString().padStart(2, '0')}"
        }.mapValues { it.value.size }
        
        // Get per-user breakdown
        val userUsageBreakdown = users.map { user ->
            val userUsageStats = runCatching { usageService.getUserUsageStats(user.id!!, days) }.getOrNull()
            
            val activeSubscription = runCatching {
                val subscriptions = userSubscriptionRepository.findByUserId(user.id!!)
                subscriptions.firstOrNull { !it.isExpired() }?.planName
            }.getOrNull()
            
            val lastActivity = userUsageStats?.dailyUsage?.keys?.maxOrNull()
            val lastActivityEpoch = lastActivity?.let {
                runCatching {
                    val date = LocalDate.parse(it)
                    val instant = LocalDateTime(date.year, date.month.number, date.day, 0, 0, 0).toInstant(kotlinx.datetime.TimeZone.UTC)
                    instant.toEpochMilliseconds()
                }.getOrNull()
            }
            
            AdminUserUsageDto(
                userId = user.id!!.value,
                userEmail = user.email.value,
                totalSearches = userUsageStats?.totalUsage ?: 0,
                activeSubscription = activeSubscription,
                lastActivityDate = lastActivityEpoch
            )
        }
        
        val response = AdminUsageStatsDto(
            totalUsers = totalUsers,
            totalApiKeys = totalApiKeys,
            totalSearches = sessions.size.toLong(),
            dailyUsage = dailyUsage,
            userUsageBreakdown = userUsageBreakdown
        )
        
        call.respond(HttpStatusCode.OK, response)
    }

    suspend fun getUserUsageStats(call: ApplicationCall) {
        val userId = call.parameters["userId"]?.toIntOrNull()
        if (userId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid user ID"))
            return
        }

        val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 30
        
        val stats = usageService.getUserUsageStats(UserId(userId), days)
        call.respond(HttpStatusCode.OK, stats)
    }
}

