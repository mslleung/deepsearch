package io.deepsearch.presentation.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.deepsearch.domain.config.JwtConfig
import io.deepsearch.presentation.controllers.PeriodicIndexJobController
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.scope
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import kotlin.io.encoding.Base64

fun Application.configurePeriodicIndexJobRoutes() {
    routing {
        authenticate("auth-jwt") {
            route("/api") {
                route("/periodic-index/jobs") {
                    get {
                        val controller = call.scope.get<PeriodicIndexJobController>()
                        controller.list(call)
                    }
                    post("/{id}/stop") {
                        val controller = call.scope.get<PeriodicIndexJobController>()
                        controller.stop(call)
                    }
                }
            }
        }
        
        // SSE endpoint with query param auth (EventSource cannot set headers)
        route("/api") {
            route("/periodic-index/jobs") {
                sse("/{id}/stream") {
                    val jwtConfig by inject<JwtConfig>()
                    val token = call.request.queryParameters["token"]
                    
                    if (token == null) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing token parameter"))
                        return@sse
                    }
                    
                    // Validate JWT token
                    try {
                        val keyFactory = KeyFactory.getInstance("EC")
                        val publicKeySpec = X509EncodedKeySpec(Base64.decode(jwtConfig.publicKey))
                        val publicKey = keyFactory.generatePublic(publicKeySpec) as ECPublicKey
                        val algorithm = Algorithm.ECDSA256(publicKey, null)
                        
                        val verifier = JWT.require(algorithm)
                            .withAudience(jwtConfig.audience)
                            .withIssuer(jwtConfig.issuer)
                            .build()
                        
                        val decodedJWT = verifier.verify(token)
                        val userId = decodedJWT.getClaim(JwtConfig.CLAIM_USER_ID).asInt()
                        
                        if (userId == null) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                            return@sse
                        }
                        
                        // Token is valid, proceed with streaming
                        val controller = call.scope.get<PeriodicIndexJobController>()
                        controller.stream(call, this)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or expired token"))
                        return@sse
                    }
                }
            }
        }
    }
}

