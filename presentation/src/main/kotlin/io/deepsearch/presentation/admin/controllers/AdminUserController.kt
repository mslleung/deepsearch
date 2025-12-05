package io.deepsearch.presentation.admin.controllers

import io.deepsearch.application.services.IUserService
import io.deepsearch.application.services.IUserSubscriptionService
import io.deepsearch.application.services.IUsageService
import io.deepsearch.domain.models.entities.User
import io.deepsearch.domain.models.valueobjects.Email
import io.deepsearch.domain.models.valueobjects.PasswordHash
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.repositories.IUserSubscriptionRepository
import io.deepsearch.presentation.admin.dto.AdminUserDto
import io.deepsearch.presentation.admin.dto.UpdateUserRequest
import io.deepsearch.presentation.admin.dto.toAdminDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.mindrot.jbcrypt.BCrypt
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class AdminUserController(
    private val userService: IUserService,
    private val userSubscriptionRepository: IUserSubscriptionRepository,
    private val usageService: IUsageService
) {

    suspend fun getAllUsers(call: ApplicationCall) {
        val users = userService.getAllUsers()
        
        // Enrich with subscription and usage data
        val usersDto = users.map { user ->
            val subscriptions = userSubscriptionRepository.findByUserId(user.id!!)
            val usageStats = runCatching { usageService.getUserUsageStats(user.id!!, 365) }.getOrNull()
            
            user.toAdminDto(
                subscriptions = subscriptions,
                totalUsage = usageStats?.totalUsage
            )
        }
        
        call.respond(HttpStatusCode.OK, usersDto)
    }

    suspend fun getUserById(call: ApplicationCall) {
        val userId = call.parameters["id"]?.toIntOrNull()
        if (userId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid user ID"))
            return
        }

        val user = userService.getUserById(UserId(userId))
        val subscriptions = userSubscriptionRepository.findByUserId(user.id!!)
        val usageStats = usageService.getUserUsageStats(user.id!!, 365)
        
        val userDto = user.toAdminDto(
            subscriptions = subscriptions,
            totalUsage = usageStats.totalUsage
        )
        
        call.respond(HttpStatusCode.OK, userDto)
    }

    suspend fun updateUser(call: ApplicationCall) {
        val userId = call.parameters["id"]?.toIntOrNull()
        if (userId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid user ID"))
            return
        }

        val request = call.receive<UpdateUserRequest>()
        val user = userService.getUserById(UserId(userId))

        // Update email if provided
        if (request.email != null) {
            val updatedUser = User(
                id = user.id,
                email = Email(request.email),
                passwordHash = user.passwordHash,
                oauthProvider = user.oauthProvider,
                oauthProviderId = user.oauthProviderId,
                createdAt = user.createdAt,
                updatedAt = kotlin.time.Clock.System.now(),
                version = user.version
            )
            userService.updateUser(UserId(userId), updatedUser)
        }

        // Update password if provided
        if (request.password != null) {
            val passwordHash = BCrypt.hashpw(request.password, BCrypt.gensalt(12))
            user.updatePassword(PasswordHash(passwordHash), kotlin.time.Clock.System.now())
            userService.updateUser(UserId(userId), user)
        }

        val updatedUser = userService.getUserById(UserId(userId))
        call.respond(HttpStatusCode.OK, updatedUser.toAdminDto())
    }

    suspend fun deleteUser(call: ApplicationCall) {
        val userId = call.parameters["id"]?.toIntOrNull()
        if (userId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid user ID"))
            return
        }

        val deleted = userService.deleteUser(UserId(userId))
        if (deleted) {
            call.respond(HttpStatusCode.OK, mapOf("message" to "User deleted successfully"))
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "User not found"))
        }
    }
}

