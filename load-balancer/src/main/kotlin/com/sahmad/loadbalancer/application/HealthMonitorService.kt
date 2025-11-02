package com.sahmad.loadbalancer.application

import com.sahmad.loadbalancer.domain.repository.NodeRepository
import com.sahmad.loadbalancer.domain.service.HealthCheckService
import com.sahmad.loadbalancer.infrastructure.config.LogAttributes
import com.sahmad.loadbalancer.infrastructure.config.LogComponents
import com.sahmad.loadbalancer.infrastructure.config.StructuredLogger
import io.opentelemetry.api.OpenTelemetry
import kotlinx.coroutines.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Service that continuously monitors the health of backend nodes.
 */
class HealthMonitorService(
    private val nodeRepository: NodeRepository,
    private val healthCheckService: HealthCheckService,
    private val checkInterval: Duration = 5.seconds,
    openTelemetry: OpenTelemetry,
) {
    private val logger =
        StructuredLogger.create(
            openTelemetry,
            LogComponents.HEALTH_CHECK,
        )
    private var monitoringJob: Job? = null

    /**
     * Start health monitoring.
     */
    fun start(scope: CoroutineScope) {
        logger.info("Starting health monitoring", mapOf("interval_seconds" to checkInterval.inWholeSeconds.toString()))

        monitoringJob =
            scope.launch {
                while (isActive) {
                    try {
                        performHealthChecks()
                    } catch (e: Exception) {
                        logger.error("Error during health check cycle", e)
                    }
                    delay(checkInterval)
                }
            }
    }

    /**
     * Stop health monitoring.
     */
    fun stop() {
        logger.info("Stopping health monitoring")
        monitoringJob?.cancel()
        monitoringJob = null
    }

    private suspend fun performHealthChecks() {
        val nodes = nodeRepository.findAll()

        if (nodes.isEmpty()) {
            logger.debug("No nodes to health check")
            return
        }

        logger.debug("Performing health checks", mapOf("node_count" to nodes.size.toString()))

        // Perform health checks in parallel
        coroutineScope {
            nodes
                .map { node ->
                    async {
                        try {
                            val result = healthCheckService.checkHealth(node)
                            val newStatus = healthCheckService.determineHealthStatus(result)
                            val previousStatus = node.getHealthStatus()

                            val event = node.updateHealthStatus(newStatus, "Health check result")
                            nodeRepository.save(node)

                            if (event != null) {
                                logger.warn(
                                    "Node health status changed",
                                    mapOf(
                                        _root_ide_package_.com.sahmad.loadbalancer.infrastructure.config.LogAttributes.NODE_ID to
                                            node.id.value,
                                        _root_ide_package_.com.sahmad.loadbalancer.infrastructure.config.LogAttributes.NODE_ENDPOINT to
                                            node.endpoint.toString(),
                                        _root_ide_package_.com.sahmad.loadbalancer.infrastructure.config.LogAttributes.HEALTH_STATUS to
                                            newStatus.name,
                                        "previous_status" to previousStatus.name,
                                        _root_ide_package_.com.sahmad.loadbalancer.infrastructure.config.LogAttributes.CIRCUIT_BREAKER_STATE to
                                            node.getCircuitBreakerState().name,
                                    ),
                                )
                            } else {
                                logger.debug(
                                    "Health check completed",
                                    mapOf(
                                        _root_ide_package_.com.sahmad.loadbalancer.infrastructure.config.LogAttributes.NODE_ID to
                                            node.id.value,
                                        _root_ide_package_.com.sahmad.loadbalancer.infrastructure.config.LogAttributes.HEALTH_STATUS to
                                            newStatus.name,
                                        _root_ide_package_.com.sahmad.loadbalancer.infrastructure.config.LogAttributes.CIRCUIT_BREAKER_STATE to
                                            node.getCircuitBreakerState().name,
                                    ),
                                )
                            }
                        } catch (e: Exception) {
                            logger.error(
                                "Health check failed for node",
                                e,
                                mapOf(
                                    _root_ide_package_.com.sahmad.loadbalancer.infrastructure.config.LogAttributes.NODE_ID to node.id.value,
                                    _root_ide_package_.com.sahmad.loadbalancer.infrastructure.config.LogAttributes.NODE_ENDPOINT to
                                        node.endpoint.toString(),
                                ),
                            )
                        }
                    }
                }.awaitAll()
        }
    }
}
