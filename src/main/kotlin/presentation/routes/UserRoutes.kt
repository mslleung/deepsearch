package io.deepsearch.presentation.routes

import io.deepsearch.presentation.controllers.UserController
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureUserRoutes() {
    val userController = UserController()
    
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