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
class RoundRobinStrategyTest {

    @Test
    fun `should return null when no nodes available`() {
        val strategy = RoundRobinStrategy()
        val result = strategy.selectNode(emptyList())

        result shouldBe null
    }

    @Test
    fun `should select nodes in round-robin fashion`() {
        val strategy = RoundRobinStrategy()
        val nodes = createTestNodes(3)

        val selections = (1..9).map { strategy.selectNode(nodes) }

        // Should cycle through nodes: 0,1,2,0,1,2,0,1,2
        selections[0] shouldBe nodes[0]
        selections[1] shouldBe nodes[1]
        selections[2] shouldBe nodes[2]
        selections[3] shouldBe nodes[0]
        selections[4] shouldBe nodes[1]
        selections[5] shouldBe nodes[2]
        selections[6] shouldBe nodes[0]
        selections[7] shouldBe nodes[1]
        selections[8] shouldBe nodes[2]
    }

    @Test
    fun `should handle single node`() {
        val strategy = RoundRobinStrategy()
        val nodes = createTestNodes(1)

        val selections = (1..5).map { strategy.selectNode(nodes) }

        selections.all { it == nodes[0] } shouldBe true
    }

    @Test
    fun `should reset counter`() {
        val strategy = RoundRobinStrategy()
        val nodes = createTestNodes(3)

        // Select a few nodes
        repeat(5) { strategy.selectNode(nodes) }

        // Reset
        strategy.reset()

        // Should start from first node again
        strategy.selectNode(nodes) shouldBe nodes[0]
    }

    private fun createTestNodes(count: Int): List<Node> {
        return (1..count).map { i ->
            Node(
                id = NodeId("node-$i"),
                endpoint = Endpoint("localhost", 9000 + i),
                weight = Weight(1)
            )
        }
    }
}

