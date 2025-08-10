package io.deepsearch.presentation.routes

import io.deepsearch.presentation.controllers.UserController
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.scope

fun Application.configureUserRoutes() {

    routing {
        route("/users") {
            post {
                val userController = call.scope.get<UserController>()
                userController.createUser(call)
            }

            get {
                val userController = call.scope.get<UserController>()
                userController.getAllUsers(call)
            }

            get("/{id}") {
                val userController = call.scope.get<UserController>()
                userController.getUserById(call)
            }

            put("/{id}") {
                val userController = call.scope.get<UserController>()
                userController.updateUser(call)
            }

            delete("/{id}") {
                val userController = call.scope.get<UserController>()
                userController.deleteUser(call)
            }
        }
    }
} 