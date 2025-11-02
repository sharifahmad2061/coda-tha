package com.sahmad.loadbalancer.presentation

import com.sahmad.loadbalancer.application.HealthMonitorService
import com.sahmad.loadbalancer.application.LoadBalancerService
import com.sahmad.loadbalancer.application.RequestResult
import com.sahmad.loadbalancer.domain.model.Endpoint
import com.sahmad.loadbalancer.domain.model.Node
import com.sahmad.loadbalancer.domain.model.NodeId
import com.sahmad.loadbalancer.domain.model.Weight
import com.sahmad.loadbalancer.domain.strategy.LoadBalancingStrategy
import com.sahmad.loadbalancer.domain.strategy.RoundRobinStrategy
import com.sahmad.loadbalancer.domain.strategy.WeightedRoundRobinStrategy
import com.sahmad.loadbalancer.infrastructure.config.OpenTelemetryConfig
import com.sahmad.loadbalancer.infrastructure.http.LoadBalancerHttpClient
import com.sahmad.loadbalancer.infrastructure.repository.InMemoryNodeRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.opentelemetry.api.GlobalOpenTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

fun main() {
    logger.info { "Starting Load Balancer..." }

    // Get OpenTelemetry instance from the Java Agent
    // Note: When running with -javaagent:opentelemetry-javaagent.jar,
    // the agent provides the global OpenTelemetry instance automatically
    val openTelemetry = GlobalOpenTelemetry.get()

    logger.info { "OpenTelemetry instance obtained from agent" }

    // Initialize dependencies
    val nodeRepository = InMemoryNodeRepository()
    val httpClient = LoadBalancerHttpClient(openTelemetry)
    val strategy: LoadBalancingStrategy = RoundRobinStrategy()
    val loadBalancerService = LoadBalancerService(nodeRepository, httpClient, strategy, openTelemetry)
    val healthMonitorService = HealthMonitorService(nodeRepository, httpClient, openTelemetry = openTelemetry)

    // Initialize nodes from config or environment
    val scope = CoroutineScope(Dispatchers.Default)
    kotlinx.coroutines.runBlocking {
        initializeNodes(nodeRepository)
    }

    // Start health monitoring
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
    configureSerialization()
    configureRouting(loadBalancerService, nodeRepository)
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

fun Application.configureRouting(
    loadBalancerService: LoadBalancerService,
    nodeRepository: InMemoryNodeRepository,
) {
    routing {
        get("/") {
            call.respondText("Load Balancer is running!")
        }

        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "healthy"))
        }

        // Forward POST requests to backend
        post("/{path...}") {
            val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
            val body = call.receiveText()
            handleForwardRequest(loadBalancerService, "/$path", HttpMethod.Post, body)
        }

        // Admin endpoints
        route("/admin") {
            get("/nodes") {
                val nodes = nodeRepository.findAll()
                val response =
                    nodes.map { node ->
                        mapOf(
                            "id" to node.id.value,
                            "endpoint" to node.endpoint.toString(),
                            "weight" to node.weight.value,
                            "health" to node.getHealthStatus().name,
                            "circuitBreaker" to node.getCircuitBreakerState().name,
                            "activeConnections" to node.getActiveConnections(),
                        )
                    }
                call.respond(response)
            }

            post("/nodes") {
                val request = call.receive<AddNodeRequest>()
                val node =
                    Node(
                        id = NodeId(request.id),
                        endpoint = Endpoint(request.host, request.port),
                        weight = Weight(request.weight),
                    )
                nodeRepository.save(node)
                call.respond(HttpStatusCode.Created, mapOf("message" to "Node added"))
            }

            delete("/nodes/{id}") {
                val nodeId = NodeId(call.parameters["id"] ?: "")
                val deleted = nodeRepository.delete(nodeId)
                if (deleted) {
                    call.respond(HttpStatusCode.OK, mapOf("message" to "Node deleted"))
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Node not found"))
                }
            }
        }

        // Metrics endpoint
        get("/metrics") {
            val nodes = nodeRepository.findAll()
            val availableNodes = nodeRepository.findAvailableNodes()

            val metrics =
                mapOf(
                    "nodes" to
                        mapOf(
                            "total" to nodes.size,
                            "available" to availableNodes.size,
                            "unavailable" to nodes.size - availableNodes.size,
                        ),
                    "nodeDetails" to
                        nodes.map { node ->
                            mapOf(
                                "id" to node.id.value,
                                "endpoint" to node.endpoint.toString(),
                                "health" to node.getHealthStatus().name,
                                "circuitBreaker" to node.getCircuitBreakerState().name,
                                "activeConnections" to node.getActiveConnections(),
                                "available" to node.isAvailable(),
                            )
                        },
                )

            call.respond(metrics)
        }
    }
}

private suspend fun RoutingContext.handleForwardRequest(
    loadBalancerService: LoadBalancerService,
    path: String,
    method: HttpMethod,
    body: String? = null,
) {
    when (val result = loadBalancerService.handleRequest(path, method, body = body)) {
        is RequestResult.Success -> {
            // Return the actual backend response to the caller
            call.respondText(
                text = result.responseBody,
                status = HttpStatusCode.fromValue(result.statusCode),
                contentType = ContentType.Application.Json,
            )
        }
        is RequestResult.RequestFailed -> {
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to result.error))
        }
        RequestResult.NoAvailableNodes -> {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "No available nodes"))
        }
        RequestResult.SelectionFailed -> {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to select node"))
        }
    }
}

private suspend fun initializeNodes(nodeRepository: InMemoryNodeRepository) {
    // Initialize with default nodes
    val defaultNodes =
        listOf(
            Node(
                id = NodeId("node-1"),
                endpoint = Endpoint("localhost", 9001),
                weight = Weight(1),
            ),
            Node(
                id = NodeId("node-2"),
                endpoint = Endpoint("localhost", 9002),
                weight = Weight(1),
            ),
            Node(
                id = NodeId("node-3"),
                endpoint = Endpoint("localhost", 9003),
                weight = Weight(1),
            ),
        )

    defaultNodes.forEach { nodeRepository.save(it) }
    logger.info { "Initialized ${defaultNodes.size} nodes" }
}

@Serializable
data class AddNodeRequest(
    val id: String,
    val host: String,
    val port: Int,
    val weight: Int = 1,
)
