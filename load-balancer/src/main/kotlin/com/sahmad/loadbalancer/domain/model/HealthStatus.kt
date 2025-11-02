package com.sahmad.loadbalancer.domain.model

/**
 * Value object representing the health status of a node.
 */
enum class HealthStatus {
    HEALTHY, // Fully operational
    DEGRADED, // Slow responses but working
    UNHEALTHY, // Not responding or timing out
    ;

    /**
     * Check if the node is usable for handling requests.
     */
    fun isUsable(): Boolean = this != UNHEALTHY

    /**
     * Check if the node is in perfect health.
     */
    fun isHealthy(): Boolean = this == HEALTHY
}
