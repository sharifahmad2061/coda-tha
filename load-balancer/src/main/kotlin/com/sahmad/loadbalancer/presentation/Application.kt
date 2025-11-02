package com.sahmad.loadbalancer.presentation

import com.sahmad.loadbalancer.application.HealthMonitorService
import com.sahmad.loadbalancer.application.LoadBalancerService
import com.sahmad.loadbalancer.domain.strategy.LoadBalancingStrategy
import com.sahmad.loadbalancer.domain.strategy.RoundRobinStrategy
import com.sahmad.loadbalancer.infrastructure.config.NodeInitializer
import com.sahmad.loadbalancer.infrastructure.http.LoadBalancerHttpClient
import com.sahmad.loadbalancer.infrastructure.logging.configureCallLogging
import com.sahmad.loadbalancer.infrastructure.repository.InMemoryNodeRepository
import com.sahmad.loadbalancer.infrastructure.serialization.configureSerialization
import com.sahmad.loadbalancer.presentation.routes.configureApiRouting
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.opentelemetry.api.GlobalOpenTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

private val logger = KotlinLogging.logger {}

fun main() {
    logger.info { "Starting Load Balancer..." }

    val openTelemetry = GlobalOpenTelemetry.get()
    logger.info { "OpenTelemetry instance obtained from agent" }

    val nodeRepository = InMemoryNodeRepository()
    val httpClient = LoadBalancerHttpClient(openTelemetry)
    val strategy: LoadBalancingStrategy = RoundRobinStrategy()
    val loadBalancerService = LoadBalancerService(nodeRepository, httpClient, strategy, openTelemetry)
    val healthMonitorService = HealthMonitorService(nodeRepository, httpClient, openTelemetry = openTelemetry)

    val scope = CoroutineScope(Dispatchers.Default)
    runBlocking { NodeInitializer.initializeNodes(nodeRepository) }
    healthMonitorService.start(scope)

    logger.info { "Services initialized" }

    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        module(loadBalancerService, nodeRepository)
    }.start(wait = true)
}

fun Application.module(
    loadBalancerService: LoadBalancerService,
    nodeRepository: InMemoryNodeRepository,
) {
    configureCallLogging()
    configureSerialization()
    configureApiRouting(loadBalancerService, nodeRepository)
}
