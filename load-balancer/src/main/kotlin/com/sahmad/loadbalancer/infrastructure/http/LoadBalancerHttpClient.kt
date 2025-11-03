package com.sahmad.loadbalancer.infrastructure.http

import com.sahmad.loadbalancer.domain.model.Node
import com.sahmad.loadbalancer.infrastructure.config.LogAttributes
import com.sahmad.loadbalancer.infrastructure.config.LogComponents
import com.sahmad.loadbalancer.infrastructure.config.StructuredLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.opentelemetry.api.OpenTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
    private val defaultTimeout: Duration,
) {
    private val logger = StructuredLogger.create(openTelemetry, LogComponents.HTTP_CLIENT)

    private val client =
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = defaultTimeout.inWholeMilliseconds
                connectTimeoutMillis = 100
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
