package com.sahmad.loadbalancer.domain.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class NodeTest {
    @Test
    fun `should initialize with healthy status by default`() {
        val node = createNode()

        node.getHealthStatus() shouldBe HealthStatus.HEALTHY
        node.isAvailable() shouldBe true
    }

    @Test
    fun `should be available when healthy or degraded`() {
        val node = createNode()

        node.updateHealthStatus(HealthStatus.HEALTHY, "test")
        node.isAvailable() shouldBe true

        node.updateHealthStatus(HealthStatus.DEGRADED, "test")
        node.isAvailable() shouldBe true
    }

    @Test
    fun `should not be available when unhealthy`() {
        val node = createNode()

        node.updateHealthStatus(HealthStatus.UNHEALTHY, "test")
        node.isAvailable() shouldBe false
    }

    @Test
    fun `should return event when health status changes`() {
        val node = createNode()

        val event = node.updateHealthStatus(HealthStatus.DEGRADED, "High latency")

        event shouldNotBe null
        event!!.nodeId shouldBe node.id
        event.previousStatus shouldBe HealthStatus.HEALTHY
        event.newStatus shouldBe HealthStatus.DEGRADED
        event.reason shouldBe "High latency"
    }

    @Test
    fun `should not return event when health status remains same`() {
        val node = createNode()

        val event = node.updateHealthStatus(HealthStatus.HEALTHY, "Still healthy")

        event shouldBe null
        node.getHealthStatus() shouldBe HealthStatus.HEALTHY
    }

    @Test
    fun `should update health status and emit events on transitions`() {
        val node = createNode()

        // HEALTHY -> DEGRADED
        val event1 = node.updateHealthStatus(HealthStatus.DEGRADED, "Slow responses")
        event1 shouldNotBe null
        event1!!.previousStatus shouldBe HealthStatus.HEALTHY
        event1.newStatus shouldBe HealthStatus.DEGRADED

        // DEGRADED -> UNHEALTHY
        val event2 = node.updateHealthStatus(HealthStatus.UNHEALTHY, "Connection refused")
        event2 shouldNotBe null
        event2!!.previousStatus shouldBe HealthStatus.DEGRADED
        event2.newStatus shouldBe HealthStatus.UNHEALTHY

        // UNHEALTHY -> DEGRADED (recovery)
        val event3 = node.updateHealthStatus(HealthStatus.DEGRADED, "Recovering")
        event3 shouldNotBe null
        event3!!.previousStatus shouldBe HealthStatus.UNHEALTHY
        event3.newStatus shouldBe HealthStatus.DEGRADED

        // DEGRADED -> HEALTHY (fully recovered)
        val event4 = node.updateHealthStatus(HealthStatus.HEALTHY, "Fully recovered")
        event4 shouldNotBe null
        event4!!.previousStatus shouldBe HealthStatus.DEGRADED
        event4.newStatus shouldBe HealthStatus.HEALTHY
    }

    private fun createNode(
        id: String = "test-node",
        host: String = "localhost",
        port: Int = 9000,
    ): Node =
        Node(
            id = NodeId(id),
            endpoint = Endpoint(host, port),
        )
}
