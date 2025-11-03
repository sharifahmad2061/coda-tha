package com.sahmad.loadbalancer.domain.service

import com.sahmad.loadbalancer.domain.model.HealthStatus
import com.sahmad.loadbalancer.domain.model.Node
import kotlin.time.Duration

/**
 * Result of a health check operation.
 */
sealed interface HealthCheckResult {
    data class Success(
        val latency: Duration,
    ) : HealthCheckResult

    data class Failure(
        val error: String,
        val latency: Duration,
    ) : HealthCheckResult
}

/**
 * Domain service for performing health checks on nodes.
 * This interface defines the contract for health checking behavior.
 */
interface HealthCheckService {
    /**
     * Perform a health check on a node.
     */
    suspend fun checkHealth(node: Node): HealthCheckResult

    /**
     * Determine health status based on check result.
     */
    fun determineHealthStatus(result: HealthCheckResult): HealthStatus =
        when (result) {
            is HealthCheckResult.Success -> {
                // Fast response = healthy
                if (result.latency.inWholeMilliseconds < 50) {
                    HealthStatus.HEALTHY
                } else {
                    // Slow but working = degraded
                    HealthStatus.DEGRADED
                }
            }
            is HealthCheckResult.Failure -> HealthStatus.UNHEALTHY
        }
}
