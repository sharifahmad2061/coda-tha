package com.sahmad.loadbalancer.domain.repository

import com.coda.loadbalancer.domain.model.Node
import com.coda.loadbalancer.domain.model.NodeId

/**
 * Repository interface for Node aggregate.
 * Provides abstraction for node persistence and retrieval.
 */
interface NodeRepository {
    /**
     * Find a node by its ID.
     */
    suspend fun findById(id: NodeId): Node?

    /**
     * Find all nodes.
     */
    suspend fun findAll(): List<Node>

    /**
     * Find all available nodes (healthy and circuit breaker allows requests).
     */
    suspend fun findAvailableNodes(): List<Node>

    /**
     * Save or update a node.
     */
    suspend fun save(node: Node)

    /**
     * Delete a node by ID.
     */
    suspend fun delete(id: NodeId): Boolean

    /**
     * Check if a node exists.
     */
    suspend fun exists(id: NodeId): Boolean
}
