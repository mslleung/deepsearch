package io.deepsearch.presentation.admin.routes

import io.deepsearch.presentation.admin.controllers.AdminSubscriptionPlanController
import io.deepsearch.presentation.admin.controllers.AdminUserSubscriptionController
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.ktor.plugin.scope

fun Application.configureAdminSubscriptionRoutes() {
    routing {
        // Subscription Plans
        route("/admin/subscription-plans") {
            get {
                val controller = call.scope.get<AdminSubscriptionPlanController>()
                controller.getAllPlans(call)
            }

            get("/{name}") {
                val controller = call.scope.get<AdminSubscriptionPlanController>()
                controller.getPlanByName(call)
            }
        }

        // User Subscriptions
        route("/admin/user-subscriptions") {
            get {
                val controller = call.scope.get<AdminUserSubscriptionController>()
                controller.getAllUserSubscriptions(call)
            }

            get("/{id}") {
                val controller = call.scope.get<AdminUserSubscriptionController>()
                controller.getUserSubscriptionById(call)
            }

            post {
                val controller = call.scope.get<AdminUserSubscriptionController>()
                controller.createUserSubscription(call)
            }

            put("/{id}") {
                val controller = call.scope.get<AdminUserSubscriptionController>()
                controller.updateUserSubscription(call)
            }

            delete("/{id}") {
                val controller = call.scope.get<AdminUserSubscriptionController>()
                controller.deleteUserSubscription(call)
            }
        }
    }
}

