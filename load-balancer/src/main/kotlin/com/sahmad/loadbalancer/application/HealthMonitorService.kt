package com.sahmad.loadbalancer.application

import com.sahmad.loadbalancer.domain.repository.NodeRepository
import com.sahmad.loadbalancer.domain.service.HealthCheckService
import com.sahmad.loadbalancer.domain.service.NodeHealthEventHandler
import com.sahmad.loadbalancer.infrastructure.config.LogAttributes
import com.sahmad.loadbalancer.infrastructure.config.LogComponents
import com.sahmad.loadbalancer.infrastructure.config.StructuredLogger
import io.opentelemetry.api.OpenTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class HealthMonitorService(
    private val nodeRepository: NodeRepository,
    private val healthCheckService: HealthCheckService,
    private val healthEventHandler: NodeHealthEventHandler,
    private val checkInterval: Duration = 5.seconds,
    openTelemetry: OpenTelemetry,
) {
    private val logger =
        StructuredLogger.create(
            openTelemetry,
            LogComponents.HEALTH_CHECK,
        )
    private var monitoringJob: Job? = null

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

                            val event = node.updateHealthStatus(newStatus, "Health check result")
                            nodeRepository.save(node)

                            if (event != null) {
                                healthEventHandler.handleHealthChange(event)
                            } else {
                                logger.debug(
                                    "Health check completed",
                                    mapOf(
                                        LogAttributes.NODE_ID to
                                            node.id.value,
                                        LogAttributes.HEALTH_STATUS to
                                            newStatus.name,
                                    ),
                                )
                            }
                        } catch (e: Exception) {
                            logger.error(
                                "Health check failed for node",
                                e,
                                mapOf(
                                    LogAttributes.NODE_ID to node.id.value,
                                    LogAttributes.NODE_ENDPOINT to
                                        node.endpoint.toString(),
                                ),
                            )
                        }
                    }
                }.awaitAll()
        }
    }
}
