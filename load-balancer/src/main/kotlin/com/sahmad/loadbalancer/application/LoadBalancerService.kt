package com.sahmad.loadbalancer.application

import com.sahmad.loadbalancer.domain.repository.NodeRepository
import com.sahmad.loadbalancer.domain.service.NodeHealthEventHandler
import com.sahmad.loadbalancer.domain.strategy.LoadBalancingStrategy
import com.sahmad.loadbalancer.infrastructure.config.LoadBalancerConfig
import com.sahmad.loadbalancer.infrastructure.config.LogAttributes
import com.sahmad.loadbalancer.infrastructure.config.LogComponents
import com.sahmad.loadbalancer.infrastructure.config.StructuredLogger
import com.sahmad.loadbalancer.infrastructure.http.ForwardResult
import com.sahmad.loadbalancer.infrastructure.http.LoadBalancerHttpClient
import io.ktor.http.HttpMethod
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter

/**
 * Application service for the load balancer.
 * Orchestrates the domain logic for routing requests.
 * Tracing is handled automatically by OpenTelemetry agent.
 */
class LoadBalancerService(
    private val nodeRepository: NodeRepository,
    private val httpClient: LoadBalancerHttpClient,
    private val strategy: LoadBalancingStrategy,
    private val healthEventHandler: NodeHealthEventHandler,
    openTelemetry: OpenTelemetry,
) {
    private val meter: Meter = openTelemetry.getMeter("load-balancer-service")
    private val logger =
        StructuredLogger.create(
            openTelemetry,
            LogComponents.LOAD_BALANCER,
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
     * Implements retry logic - on timeout or connection failure, tries a different node.
     * Tracing is automatic via OpenTelemetry agent.
     */
    suspend fun handleRequest(
        path: String,
        method: HttpMethod = HttpMethod.Get,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): RequestResult {
        requestCounter.add(1)

        return try {
            val maxAttempts = LoadBalancerConfig.Request.maxAttempts
            val excludedNodes = mutableSetOf<String>()

            repeat(maxAttempts) { attemptNumber ->
                val availableNodes =
                    nodeRepository
                        .findAvailableNodes()
                        .filter { it.id.value !in excludedNodes }

                if (availableNodes.isEmpty()) {
                    if (attemptNumber == 0) {
                        logger.warn(
                            "No available nodes to handle request",
                            mapOf(
                                LogAttributes.REQUEST_PATH to path,
                                LogAttributes.REQUEST_METHOD to method.value,
                            ),
                        )
                    } else {
                        logger.warn(
                            "No more nodes available for retry",
                            mapOf(
                                LogAttributes.REQUEST_PATH to path,
                                "attempt" to (attemptNumber + 1).toString(),
                                "excluded_nodes" to excludedNodes.size.toString(),
                            ),
                        )
                    }
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
                            "attempt" to (attemptNumber + 1).toString(),
                        ),
                    )
                    failureCounter.add(1)
                    return RequestResult.SelectionFailed
                }

                logger.info(
                    "Routing request to node ${selectedNode.id.value}",
                    mapOf(
                        LogAttributes.NODE_ID to selectedNode.id.value,
                        LogAttributes.NODE_ENDPOINT to selectedNode.endpoint.toString(),
                        LogAttributes.REQUEST_PATH to path,
                        LogAttributes.REQUEST_METHOD to method.value,
                        LogAttributes.STRATEGY to strategy.getName(),
                        "attempt" to (attemptNumber + 1).toString(),
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
                                    "attempt" to (attemptNumber + 1).toString(),
                                ),
                            )

                            successCounter.add(
                                1,
                                Attributes.builder().put(LogAttributes.NODE_ID, selectedNode.id.value).build(),
                            )
                            return RequestResult.Success(selectedNode.id.value, result.statusCode, result.latency, result.responseBody)
                        }

                        is ForwardResult.Failure -> {
                            val event = selectedNode.recordFailure(Exception(result.error))
                            nodeRepository.save(selectedNode)
                            event?.let { healthEventHandler.handleHealthChange(it) }

                            // Check if this is a retryable error (timeout, connection refused, etc.)
                            val isRetryable = isRetryableError(result.error)

                            logger.error(
                                "Request to ${selectedNode.id.value} failed",
                                null,
                                mapOf(
                                    LogAttributes.NODE_ID to selectedNode.id.value,
                                    LogAttributes.ERROR_TYPE to "forward_failure",
                                    "error_message" to result.error,
                                    "attempt" to (attemptNumber + 1).toString(),
                                    "retryable" to isRetryable.toString(),
                                ),
                            )

                            if (isRetryable && attemptNumber < maxAttempts - 1) {
                                // Exclude this node from next retry attempt
                                excludedNodes.add(selectedNode.id.value)
                                logger.info(
                                    "Retrying request on different node",
                                    mapOf(
                                        "failed_node" to selectedNode.id.value,
                                        "next_attempt" to (attemptNumber + 2).toString(),
                                    ),
                                )
                                // Continue to next iteration to retry
                            } else {
                                // Not retryable or last attempt - return failure
                                failureCounter.add(
                                    1,
                                    Attributes.builder().put(LogAttributes.NODE_ID, selectedNode.id.value).build(),
                                )
                                return RequestResult.RequestFailed(result.error)
                            }
                        }
                    }
                } finally {
                    // Decrement active connections
                    selectedNode.decrementActiveConnections()
                }
            }

            // All retries exhausted
            failureCounter.add(1)
            RequestResult.RequestFailed("All retry attempts exhausted")
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

    /**
     * Determine if an error is retryable (timeout, connection errors).
     */
    private fun isRetryableError(errorMessage: String): Boolean {
        val retryableKeywords =
            listOf(
                "timeout",
                "timed out",
                "connection refused",
                "connection reset",
                "connect exception",
                "socket timeout",
                "no route to host",
                "connection closed",
            )
        val lowerError = errorMessage.lowercase()
        return retryableKeywords.any { lowerError.contains(it) }
    }
}
