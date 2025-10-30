package io.deepsearch.presentation.admin.routes

import io.deepsearch.presentation.admin.controllers.AdminUserController
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.ktor.plugin.scope

fun Application.configureAdminUserRoutes() {
    routing {
        route("/admin/users") {
            get {
                val controller = call.scope.get<AdminUserController>()
                controller.getAllUsers(call)
            }

            get("/{id}") {
                val controller = call.scope.get<AdminUserController>()
                controller.getUserById(call)
            }

            put("/{id}") {
                val controller = call.scope.get<AdminUserController>()
                controller.updateUser(call)
            }

            delete("/{id}") {
                val controller = call.scope.get<AdminUserController>()
                controller.deleteUser(call)
            }
        }
    }
}

