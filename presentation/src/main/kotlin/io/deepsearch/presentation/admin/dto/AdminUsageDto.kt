package io.deepsearch.presentation.admin.dto

import kotlinx.serialization.Serializable

@Serializable
data class AdminUsageStatsDto(
    val totalUsers: Int,
    val totalApiKeys: Int,
    val totalSearches: Long,
    val dailyUsage: Map<String, Int>, // date to count
    val userUsageBreakdown: List<AdminUserUsageDto>
)

@Serializable
data class AdminUserUsageDto(
    val userId: Int,
    val userEmail: String,
    val totalSearches: Int,
    val activeSubscription: String?,
    val lastActivityDate: Long? // epoch millis
)

@Serializable
data class AdminApiKeyUsageDto(
    val apiKeyId: Int,
    val apiKeyName: String,
    val userId: Int,
    val userEmail: String,
    val totalUsage: Long,
    val dailyUsage: Map<String, Int>
)

