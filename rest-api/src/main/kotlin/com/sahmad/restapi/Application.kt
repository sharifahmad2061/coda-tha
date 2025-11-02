package com.sahmad.restapi

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

private val logger = LoggerFactory.getLogger("com.sahmad.restapi")

// Runtime configurable delay
private val configuredDelay = AtomicLong(0) // milliseconds

fun main() {
    val port = 8080 // Fixed port, Docker Compose will map to different host ports

    logger.info("Starting REST API on port $port")

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module()
    }.start(wait = true)
}

fun Application.module() {
    configureCallLogging()
    configureSerialization()
    configureRouting()
}

fun Application.configureCallLogging() {
    install(CallLogging) {
        level = org.slf4j.event.Level.INFO
    }
}

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = true
                isLenient = true
            },
        )
    }
}

fun Application.configureRouting() {
    routing {
        // Health endpoint
        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "healthy"))
        }

        // Config endpoint to set delay
        post("/config/delay") {
            val request = call.receive<DelayConfig>()
            configuredDelay.set(request.delayMs)

            logger.info("Delay configured to ${request.delayMs}ms")

            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "message" to "Delay configured",
                    "delayMs" to request.delayMs,
                ),
            )
        }

        // Get current config
        get("/config") {
            call.respond(
                mapOf(
                    "delayMs" to configuredDelay.get(),
                ),
            )
        }

        // Main POST endpoint that forwards any path
        post("/{path...}") {
            val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
            val body = call.receiveText()

            try {
                // Add custom attributes to MDC for structured logging
                // Note: trace_id and span_id are automatically added by OTel agent
                MDC.put("path", "/$path")
                MDC.put("method", "POST")
                MDC.put("body_size", body.length.toString())

                val currentDelay = configuredDelay.get()

                if (currentDelay > 0) {
                    logger.info("Processing request with ${currentDelay}ms delay")
                    delay(currentDelay.milliseconds)
                } else {
                    logger.info("Processing request with no delay")
                }

                // Echo back the request with some metadata
                val response =
                    ApiResponse(
                        message = "Request processed successfully",
                        path = "/$path",
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

@Serializable
data class DelayConfig(
    val delayMs: Long,
)

@Serializable
data class ApiResponse(
    val message: String,
    val path: String,
    val receivedBody: String,
    val delayApplied: Long,
)
