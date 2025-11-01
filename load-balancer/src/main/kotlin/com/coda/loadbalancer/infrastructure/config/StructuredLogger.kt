package com.coda.loadbalancer.infrastructure.config

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.Span

/**
 * Structured logger with OpenTelemetry integration.
 * Automatically includes trace context (trace_id, span_id) in all logs.
 * Provides proper labels for filtering in Loki.
 */
class StructuredLogger(
    private val otelLogger: Logger,
    private val componentName: String,
) {
    fun debug(
        message: String,
        attributes: Map<String, String> = emptyMap(),
    ) {
        log(Severity.DEBUG, message, attributes)
    }

    fun info(
        message: String,
        attributes: Map<String, String> = emptyMap(),
    ) {
        log(Severity.INFO, message, attributes)
    }

    fun warn(
        message: String,
        attributes: Map<String, String> = emptyMap(),
    ) {
        log(Severity.WARN, message, attributes)
    }

    fun error(
        message: String,
        throwable: Throwable? = null,
        attributes: Map<String, String> = emptyMap(),
    ) {
        val allAttributes =
            if (throwable != null) {
                attributes +
                    mapOf(
                        "exception.type" to throwable::class.simpleName.orEmpty(),
                        "exception.message" to (throwable.message ?: ""),
                    )
            } else {
                attributes
            }
        log(Severity.ERROR, message, allAttributes)
    }

    private fun log(
        severity: Severity,
        message: String,
        attributes: Map<String, String>,
    ) {
        val attributesBuilder =
            Attributes
                .builder()
                .put("component", componentName)

        // Automatically add trace context from current span
        val currentSpan = Span.current()
        val spanContext = currentSpan.spanContext
        if (spanContext.isValid) {
            attributesBuilder.put("trace_id", spanContext.traceId)
            attributesBuilder.put("span_id", spanContext.spanId)
        }

        // Add all custom attributes
        attributes.forEach { (key, value) ->
            attributesBuilder.put(key, value)
        }

        otelLogger
            .logRecordBuilder()
            .setSeverity(severity)
            .setBody(message)
            .setAllAttributes(attributesBuilder.build())
            .emit()
    }

    companion object {
        fun create(
            openTelemetry: OpenTelemetry,
            componentName: String,
        ): StructuredLogger {
            val logger =
                openTelemetry
                    .getLogsBridge()
                    .loggerBuilder("com.coda.loadbalancer")
                    .setInstrumentationVersion("1.0.0")
                    .build()

            return StructuredLogger(logger, componentName)
        }
    }
}

/**
 * Component names for structured logging.
 */
object LogComponents {
    const val HEALTH_CHECK = "health-check"
    const val LOAD_BALANCER = "load-balancer"
    const val CIRCUIT_BREAKER = "circuit-breaker"
    const val ROUTING = "routing"
    const val METRICS = "metrics"
    const val HTTP_CLIENT = "http-client"
}

/**
 * Common log attribute keys for filtering.
 */
object LogAttributes {
    const val NODE_ID = "node.id"
    const val NODE_ENDPOINT = "node.endpoint"
    const val HEALTH_STATUS = "health.status"
    const val CIRCUIT_BREAKER_STATE = "circuit_breaker.state"
    const val REQUEST_PATH = "request.path"
    const val REQUEST_METHOD = "request.method"
    const val RESPONSE_STATUS = "response.status"
    const val LATENCY_MS = "latency.ms"
    const val STRATEGY = "strategy"
    const val ERROR_TYPE = "error.type"
    const val TRACE_ID = "trace_id"
    const val SPAN_ID = "span_id"
}
