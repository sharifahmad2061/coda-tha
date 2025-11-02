package com.sahmad.loadbalancer.application

import com.sahmad.loadbalancer.domain.event.NodeHealthChangedEvent
import com.sahmad.loadbalancer.domain.repository.NodeRepository
import com.sahmad.loadbalancer.domain.strategy.LoadBalancingStrategy
import com.sahmad.loadbalancer.infrastructure.config.LogAttributes
import com.sahmad.loadbalancer.infrastructure.config.LogComponents
import com.sahmad.loadbalancer.infrastructure.config.StructuredLogger
import com.sahmad.loadbalancer.infrastructure.http.ForwardResult
import com.sahmad.loadbalancer.infrastructure.http.LoadBalancerHttpClient
import io.ktor.http.*
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter

/**
 * Application service for the load balancer.
 * Orchestrates the domain logic for routing requests.
 * Tracing is handled automatically by OpenTelemetry agent.
 */
class LoadBalancerService(
    private val nodeRepository: com.sahmad.loadbalancer.domain.repository.NodeRepository,
    private val httpClient: com.sahmad.loadbalancer.infrastructure.http.LoadBalancerHttpClient,
    private val strategy: com.sahmad.loadbalancer.domain.strategy.LoadBalancingStrategy,
    openTelemetry: OpenTelemetry,
) {
    private val meter: Meter = openTelemetry.getMeter("load-balancer-service")
    private val logger =
        _root_ide_package_.com.sahmad.loadbalancer.infrastructure.config.StructuredLogger.Companion.create(
            openTelemetry,
            _root_ide_package_.com.sahmad.loadbalancer.infrastructure.config.LogComponents.LOAD_BALANCER,
        )

    // Metrics
    private val requestCounter: LongCounter =
        meter
            .counterBuilder("loadbalancer.requests.total")
            .setDescription("Total number of requests processed")
            .build()

    private val successCounter: LongCounter =
        meter
            .counterBuilder("loadbalancer.requests.success")
            .setDescription("Total number of successful requests")
            .build()

    private val failureCounter: LongCounter =
        meter
            .counterBuilder("loadbalancer.requests.failed")
            .setDescription("Total number of failed requests")
            .build()

    /**
     * Handle an incoming request by routing it to an available node.
     * Tracing is automatic via OpenTelemetry agent.
     */
    suspend fun handleRequest(
        path: String,
        method: HttpMethod = HttpMethod.Get,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): com.sahmad.loadbalancer.application.RequestResult {
        requestCounter.add(1)

        return try {
            val availableNodes = nodeRepository.findAvailableNodes()

            if (availableNodes.isEmpty()) {
                logger.warn(
                    "No available nodes to handle request",
                    mapOf(
                        LogAttributes.REQUEST_PATH to path,
                        LogAttributes.REQUEST_METHOD to method.value,
                    ),
                )
                failureCounter.add(1)
                return RequestResult.NoAvailableNodes
            }

            val selectedNode = strategy.selectNode(availableNodes)

            if (selectedNode == null) {
                logger.error(
                    "Strategy failed to select a node",
                    null,
                    mapOf(
                        LogAttributes.STRATEGY to strategy.getName(),
                        "available_nodes" to availableNodes.size.toString(),
                    ),
                )
                failureCounter.add(1)
                return RequestResult.SelectionFailed
            }

            logger.info(
                "Routing request to node",
                mapOf(
                    LogAttributes.NODE_ID to selectedNode.id.value,
                    LogAttributes.NODE_ENDPOINT to selectedNode.endpoint.toString(),
                    LogAttributes.REQUEST_PATH to path,
                    LogAttributes.REQUEST_METHOD to method.value,
                    LogAttributes.STRATEGY to strategy.getName(),
                ),
            )

            // Increment active connections
            selectedNode.incrementActiveConnections()

            try {
                val result = httpClient.forwardRequest(selectedNode, path, method, headers, body)

                when (result) {
                    is ForwardResult.Success -> {
                        selectedNode.recordSuccess(result.latency)
                        nodeRepository.save(selectedNode)

                        logger.info(
                            "Request completed successfully",
                            mapOf(
                                LogAttributes.NODE_ID to selectedNode.id.value,
                                LogAttributes.RESPONSE_STATUS to result.statusCode.toString(),
                                LogAttributes.LATENCY_MS to result.latency.inWholeMilliseconds.toString(),
                            ),
                        )

                        successCounter.add(1)
                        RequestResult.Success(selectedNode.id.value, result.statusCode, result.latency, result.responseBody)
                    }

                    is ForwardResult.Failure -> {
                        val event = selectedNode.recordFailure(Exception(result.error))
                        nodeRepository.save(selectedNode)

                        event?.let { handleHealthChange(it) }

                        logger.error(
                            "Request failed",
                            null,
                            mapOf(
                                LogAttributes.NODE_ID to selectedNode.id.value,
                                LogAttributes.ERROR_TYPE to "forward_failure",
                                "error_message" to result.error,
                            ),
                        )

                        failureCounter.add(1)
                        RequestResult.RequestFailed(result.error)
                    }
                }
            } finally {
                // Decrement active connections
                selectedNode.decrementActiveConnections()
            }
        } catch (e: Exception) {
            logger.error(
                "Unexpected error handling request",
                e,
                mapOf(
                    LogAttributes.REQUEST_PATH to path,
                    LogAttributes.REQUEST_METHOD to method.value,
                ),
            )
            failureCounter.add(1)
            RequestResult.RequestFailed(e.message ?: "Unknown error")
        }
    }

    private fun handleHealthChange(event: NodeHealthChangedEvent) {
        logger.warn(
            "Node health status changed",
            mapOf(
                LogAttributes.NODE_ID to event.nodeId.value,
                LogAttributes.HEALTH_STATUS to event.newStatus.name,
                "previous_status" to event.previousStatus.name,
                "reason" to event.reason,
            ),
        )
    }
}

/**
 * Result of handling a request.
 */
sealed interface RequestResult {
    data class Success(
        val nodeId: String,
        val statusCode: Int,
        val latency: kotlin.time.Duration,
        val responseBody: String,
    ) : RequestResult

    data class RequestFailed(
        val error: String,
    ) : RequestResult

    data object NoAvailableNodes : RequestResult

    data object SelectionFailed : RequestResult
}
