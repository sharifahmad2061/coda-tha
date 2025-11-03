package com.sahmad.loadbalancer.domain.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class HealthStatusTest {
    @Test
    fun `HEALTHY status should be usable and healthy`() {
        val status = HealthStatus.HEALTHY

        status.isUsable() shouldBe true
        status.isHealthy() shouldBe true
    }

    @Test
    fun `DEGRADED status should be usable but not healthy`() {
        val status = HealthStatus.DEGRADED

        status.isUsable() shouldBe true
        status.isHealthy() shouldBe false
    }

    @Test
    fun `UNHEALTHY status should not be usable or healthy`() {
        val status = HealthStatus.UNHEALTHY

        status.isUsable() shouldBe false
        status.isHealthy() shouldBe false
    }

    @Test
    fun `should have exactly three status values`() {
        val allStatuses = HealthStatus.values()

        allStatuses.size shouldBe 3
        allStatuses.contains(HealthStatus.HEALTHY) shouldBe true
        allStatuses.contains(HealthStatus.DEGRADED) shouldBe true
        allStatuses.contains(HealthStatus.UNHEALTHY) shouldBe true
    }
}
