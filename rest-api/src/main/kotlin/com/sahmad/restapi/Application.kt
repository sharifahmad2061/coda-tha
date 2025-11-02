package com.sahmad.restapi

import com.sahmad.restapi.infrastructure.logging.configureCallLogging
import com.sahmad.restapi.infrastructure.serialization.configureSerialization
import com.sahmad.restapi.presentation.routes.configureApiRouting
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.sahmad.restapi")

fun main() {
    val port = 8080 // fixed port
    logger.info("Starting REST API on port $port")
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module()
    }.start(wait = true)
}

fun Application.module() {
    configureCallLogging()
    configureSerialization()
    configureApiRouting()
}
