package com.sahmad.loadbalancer.application

import com.sahmad.loadbalancer.domain.model.Endpoint
import com.sahmad.loadbalancer.domain.model.Node
import com.sahmad.loadbalancer.domain.model.NodeId
import com.sahmad.loadbalancer.domain.repository.NodeRepository
import com.sahmad.loadbalancer.domain.service.NodeHealthEventHandler
import com.sahmad.loadbalancer.domain.strategy.LoadBalancingStrategy
import com.sahmad.loadbalancer.infrastructure.http.ForwardResult
import com.sahmad.loadbalancer.infrastructure.http.LoadBalancerHttpClient
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.http.HttpMethod
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.GlobalOpenTelemetry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

@Tag("unit")
class LoadBalancerServiceTest {
    private lateinit var nodeRepository: NodeRepository
    private lateinit var httpClient: LoadBalancerHttpClient
    private lateinit var strategy: LoadBalancingStrategy
    private lateinit var healthEventHandler: NodeHealthEventHandler
    private lateinit var service: LoadBalancerService

    @BeforeEach
    fun setUp() {
        nodeRepository = mockk()
        httpClient = mockk()
        strategy = mockk()
        healthEventHandler = mockk(relaxed = true)

        // Mock strategy name for all tests
        every { strategy.getName() } returns "TestStrategy"

        service =
            LoadBalancerService(
                nodeRepository,
                httpClient,
                strategy,
                healthEventHandler,
                GlobalOpenTelemetry.get(),
            )
    }

    @Test
    fun `should return NoAvailableNodes when no nodes exist`() =
        runTest {
            coEvery { nodeRepository.findAvailableNodes() } returns emptyList()

            val result = service.handleRequest("/test", HttpMethod.Get)

            result.shouldBeInstanceOf<RequestResult.NoAvailableNodes>()
        }

    @Test
    fun `should return SelectionFailed when strategy returns null`() =
        runTest {
            val node = createNode("node-1")
            coEvery { nodeRepository.findAvailableNodes() } returns listOf(node)
            every { strategy.selectNode(any()) } returns null

            val result = service.handleRequest("/test", HttpMethod.Get)

            result.shouldBeInstanceOf<RequestResult.SelectionFailed>()
        }

    @Test
    fun `should forward request to selected node and return success`() =
        runTest {
            val node = createNode("node-1")
            coEvery { nodeRepository.findAvailableNodes() } returns listOf(node)
            every { strategy.selectNode(any()) } returns node
            coEvery { httpClient.forwardRequest(any(), any(), any(), any(), any()) } returns
                ForwardResult.Success(200, 50.milliseconds, """{"result": "ok"}""")

            val result = service.handleRequest("/test", HttpMethod.Post, body = """{"data": "test"}""")

            result.shouldBeInstanceOf<RequestResult.Success>()
            val success = result as RequestResult.Success
            success.nodeId shouldBe "node-1"
            success.statusCode shouldBe 200
            success.responseBody shouldBe """{"result": "ok"}"""

            coVerify { httpClient.forwardRequest(node, "/test", HttpMethod.Post, any(), """{"data": "test"}""") }
        }

    @Test
    fun `should increment and decrement active connections`() =
        runTest {
            val node = createNode("node-1")
            coEvery { nodeRepository.findAvailableNodes() } returns listOf(node)
            every { strategy.selectNode(any()) } returns node
            coEvery { httpClient.forwardRequest(any(), any(), any(), any(), any()) } returns
                ForwardResult.Success(200, 50.milliseconds, """{"result": "ok"}""")

            val initialConnections = node.getActiveConnections()
            service.handleRequest("/test", HttpMethod.Get)
            val finalConnections = node.getActiveConnections()

            initialConnections shouldBe 0
            finalConnections shouldBe 0 // Should be decremented after request completes
        }

    @Test
    fun `should retry on different node when first node fails with retryable error`() =
        runTest {
            val node1 = createNode("node-1")
            val node2 = createNode("node-2")

            // First call returns both nodes, second call returns only node2
            coEvery { nodeRepository.findAvailableNodes() } returnsMany
                listOf(
                    listOf(node1, node2),
                    listOf(node1, node2),
                )

            // Strategy returns node1 first, then node2
            every { strategy.selectNode(match { it.size == 2 && it.contains(node1) }) } returns node1
            every { strategy.selectNode(match { it.size == 1 && !it.contains(node1) }) } returns node2

            // First request fails with timeout, second succeeds
            coEvery { httpClient.forwardRequest(node1, any(), any(), any(), any()) } returns
                ForwardResult.Failure("Request timeout has expired")
            coEvery { httpClient.forwardRequest(node2, any(), any(), any(), any()) } returns
                ForwardResult.Success(200, 50.milliseconds, """{"result": "ok"}""")

            val result = service.handleRequest("/test", HttpMethod.Get)

            result.shouldBeInstanceOf<RequestResult.Success>()
            val success = result as RequestResult.Success
            success.nodeId shouldBe "node-2"

            // Verify both nodes were tried
            coVerify { httpClient.forwardRequest(node1, any(), any(), any(), any()) }
            coVerify { httpClient.forwardRequest(node2, any(), any(), any(), any()) }
        }

    @Test
    fun `should not retry on non-retryable errors`() =
        runTest {
            val node = createNode("node-1")
            coEvery { nodeRepository.findAvailableNodes() } returns listOf(node)
            every { strategy.selectNode(any()) } returns node
            coEvery { httpClient.forwardRequest(any(), any(), any(), any(), any()) } returns
                ForwardResult.Failure("Bad request")

            val result = service.handleRequest("/test", HttpMethod.Get)

            result.shouldBeInstanceOf<RequestResult.RequestFailed>()

            // Should only try once (no retry for non-retryable error)
            coVerify(exactly = 1) { httpClient.forwardRequest(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `should exhaust retries and return failure when all nodes fail`() =
        runTest {
            val node1 = createNode("node-1")
            val node2 = createNode("node-2")
            val node3 = createNode("node-3")

            coEvery { nodeRepository.findAvailableNodes() } returns listOf(node1, node2, node3)
            every { strategy.selectNode(match { it.contains(node1) }) } returns node1
            every { strategy.selectNode(match { !it.contains(node1) && it.contains(node2) }) } returns node2
            every { strategy.selectNode(match { !it.contains(node1) && !it.contains(node2) }) } returns node3

            // All nodes timeout
            coEvery { httpClient.forwardRequest(any(), any(), any(), any(), any()) } returns
                ForwardResult.Failure("Request timeout has expired")

            val result = service.handleRequest("/test", HttpMethod.Get)

            result.shouldBeInstanceOf<RequestResult.RequestFailed>()

            // Should try all 3 nodes (maxAttempts = 3)
            coVerify(exactly = 3) { httpClient.forwardRequest(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `should handle exception during request processing`() =
        runTest {
            coEvery { nodeRepository.findAvailableNodes() } throws RuntimeException("Database error")

            val result = service.handleRequest("/test", HttpMethod.Get)

            result.shouldBeInstanceOf<RequestResult.RequestFailed>()
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
