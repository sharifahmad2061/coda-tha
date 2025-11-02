package com.sahmad.restapi.presentation.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

private val logger = LoggerFactory.getLogger("com.sahmad.restapi.routes")
private val configuredDelay = AtomicLong(0)

fun Application.configureApiRouting() {
    routing {
        get("/health") { call.respond(HttpStatusCode.OK, mapOf("status" to "healthy")) }
        post("/config/delay") {
            val request = call.receive<DelayConfig>()
            configuredDelay.set(request.delayMs)
            logger.info("Delay configured to ${'$'}{request.delayMs}ms")
            call.respond(HttpStatusCode.OK, mapOf("message" to "Delay configured", "delayMs" to request.delayMs))
        }
        get("/config") { call.respond(mapOf("delayMs" to configuredDelay.get())) }
        post("/{path...}") {
            val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
            val body = call.receiveText()
            try {
                MDC.put("path", "/${'$'}path")
                MDC.put("method", "POST")
                MDC.put("body_size", body.length.toString())
                val currentDelay = configuredDelay.get()
                if (currentDelay > 0) {
                    logger.info("Processing request with ${'$'}currentDelay ms delay")
                    delay(currentDelay.milliseconds)
                } else {
                    logger.info("Processing request with no delay")
                }
                val response =
                    ApiResponse(
                        message = "Request processed successfully",
                        path = "/${'$'}path",
                        receivedBody = body,
                        delayApplied = currentDelay,
                    )
                logger.info("Request completed successfully")
                call.respond(HttpStatusCode.OK, response)
            } finally {
                MDC.clear()
            }
        }
    }
}

@Serializable data class DelayConfig(
    val delayMs: Long,
)

@Serializable data class ApiResponse(
    val message: String,
    val path: String,
    val receivedBody: String,
    val delayApplied: Long,
)
