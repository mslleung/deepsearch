package io.deepsearch.presentation.admin.controllers

import io.deepsearch.domain.models.entities.SubscriptionPlan
import io.deepsearch.presentation.admin.dto.toAdminDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

class AdminSubscriptionPlanController {

    suspend fun getAllPlans(call: ApplicationCall) {
        try {
            val plans = SubscriptionPlan.entries
            val plansDto = plans.map { it.toAdminDto() }
            call.respond(HttpStatusCode.OK, plansDto)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    suspend fun getPlanByName(call: ApplicationCall) {
        try {
            val planName = call.parameters["name"]
            if (planName == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Plan name required"))
                return
            }

            val plan = SubscriptionPlan.fromName(planName)
            if (plan == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Plan not found"))
                return
            }

            call.respond(HttpStatusCode.OK, plan.toAdminDto())
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }
}

