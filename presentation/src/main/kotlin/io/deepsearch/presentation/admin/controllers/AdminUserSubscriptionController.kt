package io.deepsearch.presentation.admin.controllers

import io.deepsearch.domain.models.entities.SubscriptionPlan
import io.deepsearch.domain.models.entities.UserSubscription
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.models.valueobjects.UserSubscriptionId
import io.deepsearch.domain.repositories.IUserSubscriptionRepository
import io.deepsearch.presentation.admin.dto.CreateUserSubscriptionRequest
import io.deepsearch.presentation.admin.dto.UpdateUserSubscriptionRequest
import io.deepsearch.presentation.admin.dto.toAdminDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class AdminUserSubscriptionController(
    private val userSubscriptionRepository: IUserSubscriptionRepository
) {

    suspend fun getAllUserSubscriptions(call: ApplicationCall) {
        val userId = call.request.queryParameters["userId"]?.toIntOrNull()
        
        val subscriptions = if (userId != null) {
            userSubscriptionRepository.findByUserId(UserId(userId))
        } else {
            // Get all subscriptions for all users
            userSubscriptionRepository.findAll()
        }
        
        val subscriptionsDto = subscriptions.map { it.toAdminDto() }
        call.respond(HttpStatusCode.OK, subscriptionsDto)
    }

    suspend fun getUserSubscriptionById(call: ApplicationCall) {
        val subscriptionId = call.parameters["id"]?.toLongOrNull()
        if (subscriptionId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid subscription ID"))
            return
        }

        val subscription = userSubscriptionRepository.findById(UserSubscriptionId(subscriptionId))
        if (subscription == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Subscription not found"))
            return
        }

        call.respond(HttpStatusCode.OK, subscription.toAdminDto())
    }

    suspend fun createUserSubscription(call: ApplicationCall) {
        val request = call.receive<CreateUserSubscriptionRequest>()
        
        val plan = SubscriptionPlan.fromName(request.planName)
        if (plan == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid plan name"))
            return
        }

        val now = kotlin.time.Clock.System.now()
        val startDate = request.startDate?.let { 
            kotlin.time.Instant.fromEpochMilliseconds(it)
        } ?: now
        
        val expiryDate = request.expiryDate?.let {
            kotlin.time.Instant.fromEpochMilliseconds(it)
        }

        val subscription = UserSubscription.fromPlan(
            userId = UserId(request.userId),
            plan = plan,
            startDate = startDate,
            expiryDate = expiryDate
        )

        val savedSubscription = userSubscriptionRepository.save(subscription)
        call.respond(HttpStatusCode.Created, savedSubscription.toAdminDto())
    }

    suspend fun updateUserSubscription(call: ApplicationCall) {
        val subscriptionId = call.parameters["id"]?.toLongOrNull()
        if (subscriptionId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid subscription ID"))
            return
        }

        val request = call.receive<UpdateUserSubscriptionRequest>()
        val subscription = userSubscriptionRepository.findById(UserSubscriptionId(subscriptionId))
        if (subscription == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Subscription not found"))
            return
        }

        // Update fields
        if (request.usedSearches != null) {
            subscription.usedSearches = request.usedSearches
        }
        if (request.expiryDate != null) {
            // Convert from epoch millis
            val newExpiry = kotlin.time.Instant.fromEpochMilliseconds(request.expiryDate)
            // Need to use reflection or create a new instance since expiryDate is val
            // For now, we'll create a new instance
            val updated = UserSubscription(
                id = subscription.id,
                userId = subscription.userId,
                planName = subscription.planName,
                tier = subscription.tier,
                maxSearches = subscription.maxSearches,
                priceUsd = subscription.priceUsd,
                usedSearches = subscription.usedSearches,
                startDate = subscription.startDate,
                expiryDate = newExpiry,
                createdAt = subscription.createdAt,
                updatedAt = kotlin.time.Clock.System.now(),
                version = subscription.version
            )
            val savedSubscription = userSubscriptionRepository.update(updated)
            call.respond(HttpStatusCode.OK, savedSubscription.toAdminDto())
            return
        }

        val savedSubscription = userSubscriptionRepository.update(subscription)
        call.respond(HttpStatusCode.OK, savedSubscription.toAdminDto())
    }

    suspend fun deleteUserSubscription(call: ApplicationCall) {
        val subscriptionId = call.parameters["id"]?.toLongOrNull()
        if (subscriptionId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid subscription ID"))
            return
        }

        val deleted = userSubscriptionRepository.delete(UserSubscriptionId(subscriptionId))
        if (deleted) {
            call.respond(HttpStatusCode.OK, mapOf("message" to "Subscription deleted successfully"))
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Subscription not found"))
        }
    }
}

