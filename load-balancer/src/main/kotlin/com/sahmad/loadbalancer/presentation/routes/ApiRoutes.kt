package com.sahmad.loadbalancer.presentation.routes

import com.sahmad.loadbalancer.application.LoadBalancerService
import com.sahmad.loadbalancer.application.RequestResult
import com.sahmad.loadbalancer.domain.model.Endpoint
import com.sahmad.loadbalancer.domain.model.Node
import com.sahmad.loadbalancer.domain.model.NodeId
import com.sahmad.loadbalancer.domain.model.Weight
import com.sahmad.loadbalancer.infrastructure.repository.InMemoryNodeRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

fun Application.configureApiRouting(
    loadBalancerService: LoadBalancerService,
    nodeRepository: InMemoryNodeRepository,
) {
    routing {
        get("/") { call.respondText("Load Balancer is running!") }
        get("/health") { call.respond(HttpStatusCode.OK, mapOf("status" to "healthy")) }
        post("/{path...}") {
            val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
            val body = call.receiveText()
            handleForwardRequest(loadBalancerService, "/$path", HttpMethod.Post, body)
        }
        route("/admin") {
            get("/nodes") {
                val nodes =
                    nodeRepository.findAll().map { node ->
                        mapOf(
                            "id" to node.id.value,
                            "endpoint" to node.endpoint.toString(),
                            "weight" to node.weight.value,
                            "health" to node.getHealthStatus().name,
                            "circuitBreaker" to node.getCircuitBreakerState().name,
                            "activeConnections" to node.getActiveConnections(),
                        )
                    }
                call.respond(nodes)
            }
            post("/nodes") {
                val request = call.receive<AddNodeRequest>()
                val node = Node(NodeId(request.id), Endpoint(request.host, request.port), Weight(request.weight))
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
        get("/metrics") {
            val nodes = nodeRepository.findAll()
            val available = nodeRepository.findAvailableNodes()
            call.respond(
                mapOf(
                    "nodes" to
                        mapOf(
                            "total" to nodes.size,
                            "available" to available.size,
                            "unavailable" to nodes.size - available.size,
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
                ),
            )
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
            try {
                val backendResponse = Json.decodeFromString<BackendApiResponse>(result.responseBody)
                val modified =
                    LoadBalancerResponse(
                        message = backendResponse.message,
                        path = backendResponse.path,
                        receivedBody = backendResponse.receivedBody,
                        processedBy = result.nodeId,
                    )
                call.respond(HttpStatusCode.fromValue(result.statusCode), modified)
            } catch (_: Exception) {
                call.respondText(
                    result.responseBody,
                    status = HttpStatusCode.fromValue(result.statusCode),
                    contentType = ContentType.Application.Json,
                )
            }
        }
        is RequestResult.RequestFailed -> call.respond(HttpStatusCode.BadGateway, mapOf("error" to result.error))
        RequestResult.NoAvailableNodes -> call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "No available nodes"))
        RequestResult.SelectionFailed -> call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to select node"))
    }
}

@Serializable data class AddNodeRequest(
    val id: String,
    val host: String,
    val port: Int,
    val weight: Int = 1,
)

@Serializable data class BackendApiResponse(
    val message: String,
    val path: String,
    val receivedBody: String,
    val delayApplied: Long,
)

@Serializable data class LoadBalancerResponse(
    val message: String,
    val path: String,
    val receivedBody: String,
    val processedBy: String,
)
