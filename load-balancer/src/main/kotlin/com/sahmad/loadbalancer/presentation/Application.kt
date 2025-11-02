package com.sahmad.loadbalancer.presentation

import com.sahmad.loadbalancer.application.HealthMonitorService
import com.sahmad.loadbalancer.application.LoadBalancerService
import com.sahmad.loadbalancer.domain.model.Endpoint
import com.sahmad.loadbalancer.domain.model.Node
import com.sahmad.loadbalancer.domain.model.NodeId
import com.sahmad.loadbalancer.domain.model.Weight
import com.sahmad.loadbalancer.domain.strategy.LoadBalancingStrategy
import com.sahmad.loadbalancer.domain.strategy.RoundRobinStrategy
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
    runBlocking { initializeNodes(nodeRepository) }
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

private suspend fun initializeNodes(nodeRepository: InMemoryNodeRepository) {
    val backendNodesEnv = System.getenv("BACKEND_NODES")
    val nodes =
        if (!backendNodesEnv.isNullOrBlank()) {
            backendNodesEnv.split(",").mapIndexed { index, nodeConfig ->
                val (host, port) = nodeConfig.trim().split(":")
                Node(
                    id = NodeId("node-${index + 1}"),
                    endpoint = Endpoint(host, port.toInt()),
                    weight = Weight(1),
                )
            }
        } else {
            listOf(
                Node(NodeId("node-1"), Endpoint("localhost", 9001), Weight(1)),
                Node(NodeId("node-2"), Endpoint("localhost", 9002), Weight(1)),
                Node(NodeId("node-3"), Endpoint("localhost", 9003), Weight(1)),
            )
        }
    nodes.forEach { nodeRepository.save(it) }
    logger.info { "Initialized ${nodes.size} nodes: ${nodes.map { "${it.id.value} -> ${it.endpoint.host}:${it.endpoint.port}" }}" }
}
