package com.sahmad.loadbalancer.domain.strategy

import com.coda.loadbalancer.domain.model.Node
import java.util.concurrent.atomic.AtomicInteger

/**
 * Weighted round-robin load balancing strategy.
 * Distributes requests based on node weights, giving more requests to higher-weighted nodes.
 *
 * For example, with weights [5, 3, 2], node1 gets 50%, node2 gets 30%, node3 gets 20%.
 */
class WeightedRoundRobinStrategy : LoadBalancingStrategy {
    private val counter = AtomicInteger(0)

    override fun selectNode(availableNodes: List<Node>): Node? {
        if (availableNodes.isEmpty()) {
            return null
        }

        // Build a weighted list where each node appears based on its weight
        val weightedList = buildWeightedList(availableNodes)

        if (weightedList.isEmpty()) {
            return null
        }

        val index = counter.getAndIncrement().mod(weightedList.size)
        return weightedList[index]
    }

    override fun getName(): String = "weighted-round-robin"

    override fun reset() {
        counter.set(0)
    }

    /**
     * Build a list where each node appears N times based on its weight.
     * This simple approach works well for small weights.
     */
    private fun buildWeightedList(nodes: List<Node>): List<Node> =
        nodes.flatMap { node ->
            List(node.weight.value) { node }
        }
}
