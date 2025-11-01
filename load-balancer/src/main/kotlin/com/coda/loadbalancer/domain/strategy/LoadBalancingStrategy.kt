package com.coda.loadbalancer.domain.strategy

import com.coda.loadbalancer.domain.model.Node

/**
 * Strategy interface for load balancing algorithms.
 * Follows the Strategy pattern for extensibility.
 */
interface LoadBalancingStrategy {
    /**
     * Select the next node to handle a request.
     *
     * @param availableNodes List of healthy nodes that can handle requests
     * @return Selected node, or null if no nodes are available
     */
    fun selectNode(availableNodes: List<Node>): Node?

    /**
     * Get the name of this strategy.
     */
    fun getName(): String

    /**
     * Reset any internal state (e.g., counters).
     */
    fun reset()
}
