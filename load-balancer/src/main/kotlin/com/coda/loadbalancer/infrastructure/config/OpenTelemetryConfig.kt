package com.coda.loadbalancer.infrastructure.config

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import java.time.Duration

/**
 * OpenTelemetry configuration for the load balancer.
 * Configures logs, metrics, and traces to be sent to OTLP collector.
 */
object OpenTelemetryConfig {
    private const val SERVICE_NAME = "load-balancer"
    private const val SERVICE_VERSION = "1.0.0"

    fun configure(
        otlpEndpoint: String = "http://localhost:4317",
        enableLogs: Boolean = true,
        enableMetrics: Boolean = true,
        enableTraces: Boolean = true,
    ): OpenTelemetry {
        // Define service resource attributes
        val resource =
            Resource
                .getDefault()
                .merge(
                    Resource.create(
                        Attributes
                            .builder()
                            .put(AttributeKey.stringKey("service.name"), SERVICE_NAME)
                            .put(AttributeKey.stringKey("service.version"), SERVICE_VERSION)
                            .put(AttributeKey.stringKey("service.namespace"), "coda")
                            .build(),
                    ),
                )

        // Configure Trace Provider
        val tracerProvider =
            if (enableTraces) {
                val spanExporter =
                    OtlpGrpcSpanExporter
                        .builder()
                        .setEndpoint(otlpEndpoint)
                        .setTimeout(Duration.ofSeconds(10))
                        .build()

                SdkTracerProvider
                    .builder()
                    .setResource(resource)
                    .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
                    .build()
            } else {
                SdkTracerProvider.builder().build()
            }

        // Configure Metrics Provider
        val meterProvider =
            if (enableMetrics) {
                val metricExporter =
                    OtlpGrpcMetricExporter
                        .builder()
                        .setEndpoint(otlpEndpoint)
                        .setTimeout(Duration.ofSeconds(10))
                        .build()

                SdkMeterProvider
                    .builder()
                    .setResource(resource)
                    .registerMetricReader(
                        PeriodicMetricReader
                            .builder(metricExporter)
                            .setInterval(Duration.ofSeconds(30))
                            .build(),
                    ).build()
            } else {
                SdkMeterProvider.builder().build()
            }

        // Configure Logger Provider
        val loggerProvider =
            if (enableLogs) {
                val logExporter =
                    OtlpGrpcLogRecordExporter
                        .builder()
                        .setEndpoint(otlpEndpoint)
                        .setTimeout(Duration.ofSeconds(10))
                        .build()

                SdkLoggerProvider
                    .builder()
                    .setResource(resource)
                    .addLogRecordProcessor(BatchLogRecordProcessor.builder(logExporter).build())
                    .build()
            } else {
                SdkLoggerProvider.builder().build()
            }

        // Build OpenTelemetry SDK
        val openTelemetry =
            OpenTelemetrySdk
                .builder()
                .setTracerProvider(tracerProvider)
                .setMeterProvider(meterProvider)
                .setLoggerProvider(loggerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .buildAndRegisterGlobal()

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(
            Thread {
                tracerProvider.close()
                meterProvider.close()
                loggerProvider.close()
            },
        )

        return openTelemetry
    }
}
