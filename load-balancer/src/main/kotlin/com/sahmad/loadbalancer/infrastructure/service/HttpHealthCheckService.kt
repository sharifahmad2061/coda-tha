package com.sahmad.loadbalancer.infrastructure.service

import com.sahmad.loadbalancer.domain.model.Node
import com.sahmad.loadbalancer.domain.service.HealthCheckResult
import com.sahmad.loadbalancer.domain.service.HealthCheckService
import com.sahmad.loadbalancer.infrastructure.config.LogAttributes
import com.sahmad.loadbalancer.infrastructure.config.LogComponents
import com.sahmad.loadbalancer.infrastructure.config.StructuredLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.opentelemetry.api.OpenTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.measureTime

/**
 * HTTP-based implementation of HealthCheckService.
 * Performs health checks by making HTTP requests to backend /health endpoints.
 */
class HttpHealthCheckService(
    openTelemetry: OpenTelemetry,
) : HealthCheckService {
    private val logger = StructuredLogger.create(openTelemetry, LogComponents.HEALTH_CHECK)

    private val client =
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 50 // Short timeout for health checks
                connectTimeoutMillis = 50
                socketTimeoutMillis = 50
            }

            engine {
                maxConnectionsCount = 100
                endpoint {
                    maxConnectionsPerRoute = 10
                    keepAliveTime = 5000
                    connectTimeout = 50
                    connectAttempts = 1
                }
            }
        }

    /**
     * Perform a health check on a node.
     * Tracing is automatic via OpenTelemetry agent.
     */
    override suspend fun checkHealth(node: Node): HealthCheckResult =
        withContext(Dispatchers.IO) {
            try {
                val url = "${node.endpoint.toUrl()}/health"
                var statusCode = 0

                val latency =
                    measureTime {
                        val response =
                            client.get(url) {
                                // Trace context automatically propagated by OTel agent
                                timeout {
                                    requestTimeoutMillis = 50
                                }
                            }
                        statusCode = response.status.value
                    }

                if (statusCode in 200..299) {
                    logger.debug(
                        "Health check passed",
                        mapOf(
                            LogAttributes.NODE_ID to node.id.value,
                            LogAttributes.NODE_ENDPOINT to node.endpoint.toString(),
                            LogAttributes.RESPONSE_STATUS to statusCode.toString(),
                            LogAttributes.LATENCY_MS to latency.inWholeMilliseconds.toString(),
                        ),
                    )
                    HealthCheckResult.Success(latency)
                } else {
                    logger.warn(
                        "Health check failed with non-2xx status",
                        mapOf(
                            LogAttributes.NODE_ID to node.id.value,
                            LogAttributes.NODE_ENDPOINT to node.endpoint.toString(),
                            LogAttributes.RESPONSE_STATUS to statusCode.toString(),
                        ),
                    )
                    HealthCheckResult.Failure("HTTP $statusCode", latency)
                }
            } catch (e: Exception) {
                logger.debug(
                    "Health check failed with exception",
                    mapOf(
                        LogAttributes.NODE_ID to node.id.value,
                        LogAttributes.NODE_ENDPOINT to node.endpoint.toString(),
                        LogAttributes.ERROR_TYPE to e::class.simpleName.orEmpty(),
                        "error_message" to (e.message ?: "Unknown error"),
                    ),
                )

                HealthCheckResult.Failure(e.message ?: "Unknown error", Duration.ZERO)
            }
        }

    fun close() {
        client.close()
    }
}
