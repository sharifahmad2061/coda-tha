package com.coda.loadbalancer.domain.strategy

import com.coda.loadbalancer.domain.model.Node
import java.util.concurrent.atomic.AtomicInteger

/**
 * Round-robin load balancing strategy.
 * Distributes requests evenly across all available nodes in a circular manner.
 */
class RoundRobinStrategy : LoadBalancingStrategy {
    private val counter = AtomicInteger(0)

    override fun selectNode(availableNodes: List<Node>): Node? {
        if (availableNodes.isEmpty()) {
            return null
        }

        val index = counter.getAndIncrement().mod(availableNodes.size)
        return availableNodes[index]
    }

    override fun getName(): String = "round-robin"

    override fun reset() {
        counter.set(0)
    }
}
