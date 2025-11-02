package com.sahmad.loadbalancer.domain.model

import com.coda.loadbalancer.domain.event.NodeHealthChangedEvent
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/**
 * Aggregate Root representing a backend service node.
 *
 * This is the core entity in the load balancer domain, responsible for:
 * - Maintaining its health status
 * - Managing circuit breaker state
 * - Tracking active connections
 * - Recording request metrics
 */
data class Node(
    val id: NodeId,
    val endpoint: Endpoint,
    val weight: Weight = Weight(1),
    private var healthStatus: HealthStatus = HealthStatus.HEALTHY,
    private val circuitBreaker: CircuitBreaker = CircuitBreaker(),
) {
    private val activeConnectionsCounter = AtomicInteger(0)

    /**
     * Check if the node is available to handle requests.
     */
    fun isAvailable(): Boolean = healthStatus.isUsable() && circuitBreaker.canAttemptRequest()

    /**
     * Get the current health status.
     */
    fun getHealthStatus(): HealthStatus = healthStatus

    /**
     * Get the circuit breaker state.
     */
    fun getCircuitBreakerState(): CircuitBreakerState = circuitBreaker.getState()

    /**
     * Get the number of active connections.
     */
    fun getActiveConnections(): Int = activeConnectionsCounter.get()

    /**
     * Increment active connections counter.
     */
    fun incrementActiveConnections() {
        activeConnectionsCounter.incrementAndGet()
    }

    /**
     * Decrement active connections counter.
     */
    fun decrementActiveConnections() {
        activeConnectionsCounter.decrementAndGet()
    }

    /**
     * Record a successful request.
     */
    fun recordSuccess(latency: Duration) {
        circuitBreaker.recordSuccess()

        // If health was degraded and we're getting successes, consider upgrading
        if (healthStatus == HealthStatus.DEGRADED &&
            circuitBreaker.getState() == CircuitBreakerState.CLOSED
        ) {
            // Could implement logic here to upgrade to HEALTHY after N successes
        }
    }

    /**
     * Record a failed request.
     * Returns an event if health status changed.
     */
    fun recordFailure(error: Throwable): NodeHealthChangedEvent? {
        val previousStatus = healthStatus
        circuitBreaker.recordFailure()

        // Update health status based on circuit breaker state
        val newStatus =
            when (circuitBreaker.getState()) {
                CircuitBreakerState.OPEN -> HealthStatus.UNHEALTHY
                CircuitBreakerState.HALF_OPEN -> HealthStatus.DEGRADED
                CircuitBreakerState.CLOSED -> {
                    // Still closed but having failures - might be degraded
                    if (circuitBreaker.getFailureCount() > 0) {
                        HealthStatus.DEGRADED
                    } else {
                        HealthStatus.HEALTHY
                    }
                }
            }

        return updateHealthStatus(newStatus, "Request failure: ${error.message}")
            ?.let { it.takeIf { previousStatus != newStatus } }
    }

    /**
     * Update the health status.
     * Returns an event if status changed.
     */
    fun updateHealthStatus(
        newStatus: HealthStatus,
        reason: String,
    ): NodeHealthChangedEvent? {
        if (healthStatus == newStatus) {
            return null
        }

        val previousStatus = healthStatus
        healthStatus = newStatus

        return NodeHealthChangedEvent(
            nodeId = id,
            previousStatus = previousStatus,
            newStatus = newStatus,
            reason = reason,
        )
    }

    /**
     * Reset the circuit breaker (for administrative actions).
     */
    fun resetCircuitBreaker() {
        circuitBreaker.reset()
        if (healthStatus == HealthStatus.UNHEALTHY) {
            healthStatus = HealthStatus.DEGRADED
        }
    }

    override fun toString(): String =
        "Node(id=$id, endpoint=$endpoint, health=$healthStatus, " +
            "circuitBreaker=${circuitBreaker.getState()}, " +
            "activeConnections=${getActiveConnections()})"
}
