package com.sahmad.loadbalancer.domain.model

import com.sahmad.loadbalancer.domain.event.NodeHealthChangedEvent

/**
 * Aggregate Root representing a backend service node.
 *
 * This is the core entity in the load balancer domain, responsible for:
 * - Maintaining its health status
 * - Recording request metrics
 *
 * Health management is handled by the Health Check Service which periodically
 * polls the /health endpoint and updates the health status accordingly.
 *
 * Note: Node weights are NOT part of the node model.
 * Weight-based load balancing is a strategy concern, not a domain entity concern.
 */
data class Node(
    val id: NodeId,
    val endpoint: Endpoint,
    private var healthStatus: HealthStatus = HealthStatus.HEALTHY,
) {
    /**
     * Check if the node is available to handle requests.
     * Only based on health status - health check service manages this.
     */
    fun isAvailable(): Boolean = healthStatus.isUsable()

    /**
     * Get the current health status.
     */
    fun getHealthStatus(): HealthStatus = healthStatus

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

    override fun toString(): String = "Node(id=$id, endpoint=$endpoint, health=$healthStatus)"
}
