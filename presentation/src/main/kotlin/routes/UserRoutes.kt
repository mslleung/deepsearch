package io.deepsearch.presentation.routes

import io.deepsearch.presentation.controllers.UserController
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Application.configureUserRoutes() {
    val userController by inject<UserController>()

    routing {
        route("/users") {
            post {
                userController.createUser(call)
            }

            get {
                userController.getAllUsers(call)
            }

            get("/{id}") {
                userController.getUserById(call)
            }

            put("/{id}") {
                userController.updateUser(call)
            }

            delete("/{id}") {
                userController.deleteUser(call)
            }
        }
    }
} 