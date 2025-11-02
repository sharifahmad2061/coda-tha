package com.sahmad.loadbalancer.infrastructure.http

import com.coda.loadbalancer.domain.model.Node
import com.coda.loadbalancer.domain.service.HealthCheckResult
import com.coda.loadbalancer.domain.service.HealthCheckService
import com.coda.loadbalancer.infrastructure.config.LogAttributes
import com.coda.loadbalancer.infrastructure.config.LogComponents
import com.coda.loadbalancer.infrastructure.config.StructuredLogger
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.opentelemetry.api.OpenTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * HTTP client for making requests to backend nodes.
 * Includes structured logging with automatic trace context.
 *
 * When running with OpenTelemetry Java Agent:
 * - Trace context is automatically propagated to backend requests
 * - Backend spans will appear in Tempo as child spans
 * - No manual propagation needed!
 */
class LoadBalancerHttpClient(
    private val openTelemetry: OpenTelemetry,
    private val defaultTimeout: Duration = 5.seconds,
) : HealthCheckService {
    private val logger = StructuredLogger.create(openTelemetry, LogComponents.HTTP_CLIENT)

    private val client =
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = defaultTimeout.inWholeMilliseconds
                connectTimeoutMillis = 3000
                socketTimeoutMillis = defaultTimeout.inWholeMilliseconds
            }

            engine {
                maxConnectionsCount = 1000
                endpoint {
                    maxConnectionsPerRoute = 100
                    keepAliveTime = 5000
                    connectTimeout = 3000
                    connectAttempts = 2
                }
            }
        }

    /**
     * Forward a request to a backend node.
     * Tracing is automatic via OpenTelemetry agent.
     */
    suspend fun forwardRequest(
        node: Node,
        path: String,
        method: HttpMethod,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): ForwardResult =
        withContext(Dispatchers.IO) {
            try {
                val url = "${node.endpoint.toUrl()}$path"
                var responseStatus = 0
                var responseBody = ""

                val latency =
                    measureTime {
                        val response =
                            client.request(url) {
                                this.method = method
                                // Trace context automatically propagated by OTel agent

                                headers.forEach { (key, value) ->
                                    header(key, value)
                                }
                                body?.let {
                                    setBody(it)
                                    contentType(ContentType.Application.Json)
                                }
                            }
                        responseStatus = response.status.value
                        responseBody = response.bodyAsText()
                    }

                logger.info(
                    "Request forwarded successfully",
                    mapOf(
                        LogAttributes.NODE_ID to node.id.value,
                        LogAttributes.NODE_ENDPOINT to node.endpoint.toString(),
                        LogAttributes.REQUEST_PATH to path,
                        LogAttributes.REQUEST_METHOD to method.value,
                        LogAttributes.RESPONSE_STATUS to responseStatus.toString(),
                        LogAttributes.LATENCY_MS to latency.inWholeMilliseconds.toString(),
                    ),
                )

                ForwardResult.Success(responseStatus, latency, responseBody)
            } catch (e: Exception) {
                logger.error(
                    "Request forwarding failed",
                    e,
                    mapOf(
                        LogAttributes.NODE_ID to node.id.value,
                        LogAttributes.NODE_ENDPOINT to node.endpoint.toString(),
                        LogAttributes.REQUEST_PATH to path,
                        LogAttributes.REQUEST_METHOD to method.value,
                        LogAttributes.ERROR_TYPE to e::class.simpleName.orEmpty(),
                    ),
                )

                ForwardResult.Failure(e.message ?: "Unknown error")
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
                                    requestTimeoutMillis = 2000 // Shorter timeout for health checks
                                }
                            }
                        statusCode = response.status.value
                    }

                if (statusCode in 200..299) {
                    logger.info(
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
                logger.error(
                    "Health check failed with exception",
                    e,
                    mapOf(
                        LogAttributes.NODE_ID to node.id.value,
                        LogAttributes.NODE_ENDPOINT to node.endpoint.toString(),
                        LogAttributes.ERROR_TYPE to e::class.simpleName.orEmpty(),
                    ),
                )

                HealthCheckResult.Failure(e.message ?: "Unknown error", Duration.ZERO)
            }
        }

    fun close() {
        client.close()
    }
}

/**
 * Result of forwarding a request.
 */
sealed interface ForwardResult {
    data class Success(
        val statusCode: Int,
        val latency: Duration,
        val responseBody: String,
    ) : ForwardResult

    data class Failure(
        val error: String,
    ) : ForwardResult
}
