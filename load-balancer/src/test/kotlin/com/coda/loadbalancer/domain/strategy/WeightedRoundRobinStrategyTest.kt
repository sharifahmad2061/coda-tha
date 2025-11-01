package com.coda.loadbalancer.domain.strategy

import com.coda.loadbalancer.domain.model.Endpoint
import com.coda.loadbalancer.domain.model.Node
import com.coda.loadbalancer.domain.model.NodeId
import com.coda.loadbalancer.domain.model.Weight
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class WeightedRoundRobinStrategyTest {
    @Test
    fun `should return null when no nodes available`() {
        val strategy = WeightedRoundRobinStrategy()
        val result = strategy.selectNode(emptyList())

        result shouldBe null
    }

    @Test
    fun `should distribute requests according to weights`() {
        val strategy = WeightedRoundRobinStrategy()
        val nodes =
            listOf(
                Node(
                    id = NodeId("node-1"),
                    endpoint = Endpoint("localhost", 9001),
                    weight = Weight(5), // 50% of traffic
                ),
                Node(
                    id = NodeId("node-2"),
                    endpoint = Endpoint("localhost", 9002),
                    weight = Weight(3), // 30% of traffic
                ),
                Node(
                    id = NodeId("node-3"),
                    endpoint = Endpoint("localhost", 9003),
                    weight = Weight(2), // 20% of traffic
                ),
            )

        // Total weight = 10
        // Select 100 times and verify distribution
        val selections = (1..100).map { strategy.selectNode(nodes) }

        val node1Count = selections.count { it == nodes[0] }
        val node2Count = selections.count { it == nodes[1] }
        val node3Count = selections.count { it == nodes[2] }

        // Node 1 should get approximately 50 requests
        // Node 2 should get approximately 30 requests
        // Node 3 should get approximately 20 requests
        node1Count shouldBe 50
        node2Count shouldBe 30
        node3Count shouldBe 20
    }

    @Test
    fun `should handle equal weights like round robin`() {
        val strategy = WeightedRoundRobinStrategy()
        val nodes = createTestNodes(3, weight = 1)

        val selections = (1..9).map { strategy.selectNode(nodes) }

        // With equal weights, should behave like round-robin
        selections[0] shouldBe nodes[0]
        selections[1] shouldBe nodes[1]
        selections[2] shouldBe nodes[2]
        selections[3] shouldBe nodes[0]
    }

    @Test
    fun `should handle single node`() {
        val strategy = WeightedRoundRobinStrategy()
        val nodes = createTestNodes(1, weight = 5)

        val selections = (1..10).map { strategy.selectNode(nodes) }

        selections.all { it == nodes[0] } shouldBe true
    }

    @Test
    fun `should reset counter`() {
        val strategy = WeightedRoundRobinStrategy()
        val nodes = createTestNodes(2, weight = 2)

        // Select a few nodes
        repeat(10) { strategy.selectNode(nodes) }

        // Reset
        strategy.reset()

        // Should start from first node again
        strategy.selectNode(nodes) shouldBe nodes[0]
    }

    private fun createTestNodes(
        count: Int,
        weight: Int = 1,
    ): List<Node> =
        (1..count).map { i ->
            Node(
                id = NodeId("node-$i"),
                endpoint = Endpoint("localhost", 9000 + i),
                weight = Weight(weight),
            )
        }
}
