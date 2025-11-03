package com.sahmad.loadbalancer.domain.model

import com.sahmad.loadbalancer.domain.event.NodeHealthChangedEvent

/**
 * Aggregate Root representing a backend service node.
 */
data class Node(
    val id: NodeId,
    val endpoint: Endpoint,
    private var healthStatus: HealthStatus = HealthStatus.HEALTHY,
) {
    fun isAvailable(): Boolean = healthStatus.isUsable()

    fun getHealthStatus(): HealthStatus = healthStatus

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
