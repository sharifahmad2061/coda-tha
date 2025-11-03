package com.sahmad.loadbalancer.domain.service

import com.sahmad.loadbalancer.domain.model.HealthStatus
import com.sahmad.loadbalancer.domain.model.Node
import kotlin.time.Duration

sealed interface HealthCheckResult {
    data class Success(
        val latency: Duration,
    ) : HealthCheckResult

    data class Failure(
        val error: String,
        val latency: Duration,
    ) : HealthCheckResult
}

interface HealthCheckService {
    suspend fun checkHealth(node: Node): HealthCheckResult

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
