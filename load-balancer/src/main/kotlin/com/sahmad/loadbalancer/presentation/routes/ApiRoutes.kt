package com.sahmad.loadbalancer.presentation.routes

import com.sahmad.loadbalancer.application.LoadBalancerService
import com.sahmad.loadbalancer.application.RequestResult
import com.sahmad.loadbalancer.domain.model.Endpoint
import com.sahmad.loadbalancer.domain.model.Node
import com.sahmad.loadbalancer.domain.model.NodeId
import com.sahmad.loadbalancer.infrastructure.repository.InMemoryNodeRepository
import com.sahmad.loadbalancer.presentation.routes.models.AddNodeRequest
import com.sahmad.loadbalancer.presentation.routes.models.ErrorResponse
import com.sahmad.loadbalancer.presentation.routes.models.MessageResponse
import com.sahmad.loadbalancer.presentation.routes.models.MetricsResponse
import com.sahmad.loadbalancer.presentation.routes.models.NodeDetailResponse
import com.sahmad.loadbalancer.presentation.routes.models.NodeMetricsSummary
import com.sahmad.loadbalancer.presentation.routes.models.NodeResponse
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
                        NodeResponse(
                            id = node.id.value,
                            endpoint = node.endpoint.toString(),
                            health = node.getHealthStatus().name,
                        )
                    }
                call.respond(nodes)
            }
            post("/nodes") {
                val request = call.receive<AddNodeRequest>()
                val node =
                    Node(
                        id = NodeId(request.id),
                        endpoint = Endpoint(request.host, request.port),
                    )
                nodeRepository.save(node)
                call.respond(HttpStatusCode.Created, MessageResponse(message = "Node added"))
            }
            delete("/nodes/{id}") {
                val nodeId = NodeId(call.parameters["id"] ?: "")
                val deleted = nodeRepository.delete(nodeId)
                if (deleted) {
                    call.respond(HttpStatusCode.OK, MessageResponse(message = "Node deleted"))
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "Node not found"))
                }
            }
        }
        get("/metrics") {
            val nodes = nodeRepository.findAll()
            val available = nodeRepository.findAvailableNodes()

            val nodeDetails =
                nodes.map { node ->
                    NodeDetailResponse(
                        id = node.id.value,
                        endpoint = node.endpoint.toString(),
                        health = node.getHealthStatus().name,
                        available = node.isAvailable(),
                    )
                }

            val response =
                MetricsResponse(
                    nodes =
                        NodeMetricsSummary(
                            total = nodes.size,
                            available = available.size,
                            unavailable = nodes.size - available.size,
                        ),
                    nodeDetails = nodeDetails,
                )

            call.respond(response)
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
                call.respondText(
                    result.responseBody,
                    status = HttpStatusCode.OK,
                    contentType = ContentType.Application.Json,
                )
            } catch (_: Exception) {
                call.respondText(
                    result.responseBody,
                    status = HttpStatusCode.fromValue(result.statusCode),
                    contentType = ContentType.Application.Json,
                )
            }
        }
        is RequestResult.RequestFailed -> call.respond(HttpStatusCode.BadGateway, ErrorResponse(error = result.error))
        RequestResult.NoAvailableNodes -> call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(error = "No available nodes"))
        RequestResult.SelectionFailed -> call.respond(HttpStatusCode.InternalServerError, ErrorResponse(error = "Failed to select node"))
    }
}
