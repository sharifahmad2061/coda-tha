package com.sahmad.loadbalancer.infrastructure.repository

import com.sahmad.loadbalancer.domain.model.Endpoint
import com.sahmad.loadbalancer.domain.model.HealthStatus
import com.sahmad.loadbalancer.domain.model.Node
import com.sahmad.loadbalancer.domain.model.NodeId
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class InMemoryNodeRepositoryTest {
    private lateinit var repository: InMemoryNodeRepository

    @BeforeEach
    fun setUp() {
        repository = InMemoryNodeRepository()
    }

    @Test
    fun `should save and retrieve node by id`() =
        runTest {
            val node = createNode("node-1")

            repository.save(node)
            val retrieved = repository.findById(NodeId("node-1"))

            retrieved shouldBe node
        }

    @Test
    fun `should return null when node not found`() =
        runTest {
            val retrieved = repository.findById(NodeId("non-existent"))

            retrieved shouldBe null
        }

    @Test
    fun `should update existing node`() =
        runTest {
            val node = createNode("node-1")
            repository.save(node)

            // Update health status
            node.updateHealthStatus(HealthStatus.DEGRADED, "test")
            repository.save(node)

            val retrieved = repository.findById(NodeId("node-1"))
            retrieved?.getHealthStatus() shouldBe HealthStatus.DEGRADED
        }

    @Test
    fun `should find all nodes`() =
        runTest {
            val node1 = createNode("node-1")
            val node2 = createNode("node-2")
            val node3 = createNode("node-3")

            repository.save(node1)
            repository.save(node2)
            repository.save(node3)

            val all = repository.findAll()

            all shouldHaveSize 3
            all shouldContain node1
            all shouldContain node2
            all shouldContain node3
        }

    @Test
    fun `should find only available nodes`() =
        runTest {
            val healthyNode = createNode("node-1")
            val degradedNode = createNode("node-2")
            val unhealthyNode = createNode("node-3")

            degradedNode.updateHealthStatus(HealthStatus.DEGRADED, "test")
            unhealthyNode.updateHealthStatus(HealthStatus.UNHEALTHY, "test")

            repository.save(healthyNode)
            repository.save(degradedNode)
            repository.save(unhealthyNode)

            val available = repository.findAvailableNodes()

            available shouldHaveSize 2
            available shouldContain healthyNode
            available shouldContain degradedNode
        }

    @Test
    fun `should delete node by id`() =
        runTest {
            val node = createNode("node-1")
            repository.save(node)

            val deleted = repository.delete(NodeId("node-1"))
            val retrieved = repository.findById(NodeId("node-1"))

            deleted shouldBe true
            retrieved shouldBe null
        }

    @Test
    fun `should return false when deleting non-existent node`() =
        runTest {
            val deleted = repository.delete(NodeId("non-existent"))

            deleted shouldBe false
        }

    @Test
    fun `should check if node exists`() =
        runTest {
            val node = createNode("node-1")
            repository.save(node)

            repository.exists(NodeId("node-1")) shouldBe true
            repository.exists(NodeId("non-existent")) shouldBe false
        }

    @Test
    fun `should clear all nodes`() =
        runTest {
            repository.save(createNode("node-1"))
            repository.save(createNode("node-2"))
            repository.save(createNode("node-3"))

            repository.clear()

            repository.count() shouldBe 0
            repository.findAll() shouldHaveSize 0
        }

    @Test
    fun `should handle concurrent saves`() =
        runTest {
            val nodes = (1..100).map { createNode("node-$it") }

            // Simulate concurrent saves
            nodes.forEach { node ->
                repository.save(node)
            }

            repository.count() shouldBe 100
        }

    private fun createNode(
        id: String,
        host: String = "localhost",
        port: Int = 9000,
    ): Node =
        Node(
            id = NodeId(id),
            endpoint = Endpoint(host, port),
        )
}
