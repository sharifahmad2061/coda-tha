package com.sahmad.loadbalancer.infrastructure.repository

import com.coda.loadbalancer.domain.model.Node
import com.coda.loadbalancer.domain.model.NodeId
import com.coda.loadbalancer.domain.repository.NodeRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory implementation of NodeRepository.
 * Thread-safe using ConcurrentHashMap and Mutex for complex operations.
 */
class InMemoryNodeRepository : NodeRepository {
    private val nodes = ConcurrentHashMap<NodeId, Node>()
    private val mutex = Mutex()

    override suspend fun findById(id: NodeId): Node? = nodes[id]

    override suspend fun findAll(): List<Node> = nodes.values.toList()

    override suspend fun findAvailableNodes(): List<Node> = nodes.values.filter { it.isAvailable() }

    override suspend fun save(node: Node) {
        mutex.withLock {
            nodes[node.id] = node
        }
    }

    override suspend fun delete(id: NodeId): Boolean = nodes.remove(id) != null

    override suspend fun exists(id: NodeId): Boolean = nodes.containsKey(id)

    /**
     * Clear all nodes (useful for testing).
     */
    fun clear() {
        nodes.clear()
    }

    /**
     * Get the count of nodes.
     */
    fun count(): Int = nodes.size
}
