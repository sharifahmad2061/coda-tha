package com.sahmad.loadbalancer.domain.service

import com.sahmad.loadbalancer.domain.event.NodeHealthChangedEvent
import com.sahmad.loadbalancer.infrastructure.config.LogAttributes
import com.sahmad.loadbalancer.infrastructure.config.LogComponents
import com.sahmad.loadbalancer.infrastructure.config.StructuredLogger
import io.opentelemetry.api.OpenTelemetry

/**
 * Domain service for handling node health status change events.
 * Provides centralized logging for health status changes across the system.
 */
class NodeHealthEventHandler(
    openTelemetry: OpenTelemetry,
) {
    private val logger =
        StructuredLogger.create(
            openTelemetry,
            LogComponents.HEALTH_CHECK,
        )

    /**
     * Handle a health status change event.
     */
    fun handleHealthChange(event: NodeHealthChangedEvent) {
        logger.warn(
            event.toString(),
            mapOf(
                LogAttributes.NODE_ID to event.nodeId.value,
                LogAttributes.HEALTH_STATUS to event.newStatus.name,
                "previous_status" to event.previousStatus.name,
                "reason" to event.reason,
            ),
        )
    }
}
