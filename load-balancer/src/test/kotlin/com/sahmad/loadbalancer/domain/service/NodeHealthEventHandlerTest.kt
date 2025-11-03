package com.sahmad.loadbalancer.domain.service

import com.sahmad.loadbalancer.domain.event.NodeHealthChangedEvent
import com.sahmad.loadbalancer.domain.model.HealthStatus
import com.sahmad.loadbalancer.domain.model.NodeId
import io.opentelemetry.api.GlobalOpenTelemetry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class NodeHealthEventHandlerTest {
    private lateinit var handler: NodeHealthEventHandler

    @BeforeEach
    fun setUp() {
        handler = NodeHealthEventHandler(GlobalOpenTelemetry.get())
    }

    @Test
    fun `should handle health degradation event`() {
        val event =
            NodeHealthChangedEvent(
                nodeId = NodeId("node-1"),
                previousStatus = HealthStatus.HEALTHY,
                newStatus = HealthStatus.DEGRADED,
                reason = "High latency detected",
            )

        // Should not throw exception
        handler.handleHealthChange(event)
    }

    @Test
    fun `should handle unhealthy event`() {
        val event =
            NodeHealthChangedEvent(
                nodeId = NodeId("node-1"),
                previousStatus = HealthStatus.DEGRADED,
                newStatus = HealthStatus.UNHEALTHY,
                reason = "Connection timeout",
            )

        // Should not throw exception
        handler.handleHealthChange(event)
    }

    @Test
    fun `should handle recovery event`() {
        val event =
            NodeHealthChangedEvent(
                nodeId = NodeId("node-1"),
                previousStatus = HealthStatus.UNHEALTHY,
                newStatus = HealthStatus.HEALTHY,
                reason = "Service recovered",
            )

        // Should not throw exception
        handler.handleHealthChange(event)
    }

    @Test
    fun `should handle multiple events in sequence`() {
        val events =
            listOf(
                NodeHealthChangedEvent(
                    NodeId("node-1"),
                    HealthStatus.HEALTHY,
                    HealthStatus.DEGRADED,
                    "Slow responses",
                ),
                NodeHealthChangedEvent(
                    NodeId("node-1"),
                    HealthStatus.DEGRADED,
                    HealthStatus.UNHEALTHY,
                    "Connection failed",
                ),
                NodeHealthChangedEvent(
                    NodeId("node-1"),
                    HealthStatus.UNHEALTHY,
                    HealthStatus.DEGRADED,
                    "Partial recovery",
                ),
                NodeHealthChangedEvent(
                    NodeId("node-1"),
                    HealthStatus.DEGRADED,
                    HealthStatus.HEALTHY,
                    "Full recovery",
                ),
            )

        // Should handle all events without throwing exception
        events.forEach { event ->
            handler.handleHealthChange(event)
        }
    }
}
