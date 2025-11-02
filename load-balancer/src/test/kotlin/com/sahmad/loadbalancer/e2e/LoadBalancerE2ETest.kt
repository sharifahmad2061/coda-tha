package com.sahmad.loadbalancer.e2e

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.sahmad.loadbalancer.application.HealthMonitorService
import com.sahmad.loadbalancer.application.LoadBalancerService
import com.sahmad.loadbalancer.application.RequestResult
import com.sahmad.loadbalancer.domain.model.Endpoint
import com.sahmad.loadbalancer.domain.model.Node
import com.sahmad.loadbalancer.domain.model.NodeId
import com.sahmad.loadbalancer.domain.strategy.RoundRobinStrategy
import com.sahmad.loadbalancer.infrastructure.http.LoadBalancerHttpClient
import com.sahmad.loadbalancer.infrastructure.repository.InMemoryNodeRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.http.HttpMethod
import io.opentelemetry.api.GlobalOpenTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Tag("e2e")
class LoadBalancerE2ETest {
    private lateinit var wireMockServer1: WireMockServer
    private lateinit var wireMockServer2: WireMockServer
    private lateinit var wireMockServer3: WireMockServer

    private lateinit var nodeRepository: InMemoryNodeRepository
    private lateinit var httpClient: LoadBalancerHttpClient
    private lateinit var loadBalancerService: LoadBalancerService
    private lateinit var healthMonitor: HealthMonitorService

    private val openTelemetry = GlobalOpenTelemetry.get()

    @BeforeEach
    fun setUp() {
        // Start 3 WireMock servers on different ports
        wireMockServer1 = WireMockServer(wireMockConfig().port(8091))
        wireMockServer2 = WireMockServer(wireMockConfig().port(8092))
        wireMockServer3 = WireMockServer(wireMockConfig().port(8093))

        wireMockServer1.start()
        wireMockServer2.start()
        wireMockServer3.start()

        // Initialize load balancer components with 300ms timeout
        nodeRepository = InMemoryNodeRepository()
        httpClient = LoadBalancerHttpClient(openTelemetry, defaultTimeout = 300.milliseconds)
        val strategy = RoundRobinStrategy()
        loadBalancerService = LoadBalancerService(nodeRepository, httpClient, strategy, openTelemetry)
        healthMonitor =
            HealthMonitorService(
                nodeRepository,
                httpClient,
                checkInterval = 5.seconds,
                openTelemetry,
            )
    }

    @AfterEach
    fun tearDown() {
        wireMockServer1.stop()
        wireMockServer2.stop()
        wireMockServer3.stop()
    }

    @Test
    fun `should handle all backends responding normally`() =
        runTest {
            // Given: 3 healthy backends
            setupHealthyBackend(wireMockServer1, "backend-1")
            setupHealthyBackend(wireMockServer2, "backend-2")
            setupHealthyBackend(wireMockServer3, "backend-3")

            addNodes()

            // When: Making 6 requests (2 per backend with round-robin)
            val results =
                (1..6).map {
                    loadBalancerService.handleRequest("/test", HttpMethod.Post, body = """{"request": $it}""")
                }

            // Then: All requests should succeed
            results.forEach { result ->
                assert(result is RequestResult.Success) { "Expected Success but got $result" }
            }

            // Verify round-robin distribution
            val successResults = results.filterIsInstance<RequestResult.Success>()
            successResults.size shouldBe 6

            // Each backend should have received 2 requests
            wireMockServer1.verify(2, postRequestedFor(urlEqualTo("/test")))
            wireMockServer2.verify(2, postRequestedFor(urlEqualTo("/test")))
            wireMockServer3.verify(2, postRequestedFor(urlEqualTo("/test")))
        }

    @Test
    fun `should timeout on slow backend and retry on different backend`() =
        runTest {
            // Given: backend-1 is slow (500ms), backend-2 and backend-3 are fast
            setupSlowBackend(wireMockServer1, delayMs = 500)
            setupHealthyBackend(wireMockServer2, "backend-2")
            setupHealthyBackend(wireMockServer3, "backend-3")

            addNodes()

            // When: Making a request (will hit slow backend-1 first, then retry on backend-2)
            val result = loadBalancerService.handleRequest("/test", HttpMethod.Post, body = """{"test": "timeout"}""")

            // Then: Should succeed after retry
            assert(result is RequestResult.Success) { "Expected Success but got $result" }
            val success = result as RequestResult.Success
            success.responseBody shouldContain "backend"

            // Verify backend-1 was called and timed out, then backend-2 succeeded
            delay(100) // Give time for WireMock to record
            wireMockServer1.verify(1, postRequestedFor(urlEqualTo("/test")))
            // One of the other backends should have handled the retry
            val totalRetries =
                wireMockServer2.countRequestsMatching(postRequestedFor(urlEqualTo("/test")).build()).count +
                    wireMockServer3.countRequestsMatching(postRequestedFor(urlEqualTo("/test")).build()).count
            totalRetries shouldBe 1
        }

    @Test
    fun `should handle one backend being down`() =
        runTest {
            // Given: backend-1 is down (returns 503), backend-2 and backend-3 are healthy
            setupDownBackend(wireMockServer1)
            setupHealthyBackend(wireMockServer2, "backend-2")
            setupHealthyBackend(wireMockServer3, "backend-3")

            addNodes()

            // When: Making 6 requests
            val results =
                (1..6).map {
                    loadBalancerService.handleRequest("/test", HttpMethod.Post, body = """{"request": $it}""")
                }

            // Then: Requests should eventually succeed on healthy backends
            val successCount = results.filterIsInstance<RequestResult.Success>().size

            // With retries, most requests should succeed
            assert(successCount > 3) { "Expected more than 3 successful requests, got $successCount" }
        }

    @Test
    fun `should exhaust retries when all backends are slow`() =
        runTest {
            // Given: All backends are too slow (>300ms timeout)
            setupSlowBackend(wireMockServer1, delayMs = 500)
            setupSlowBackend(wireMockServer2, delayMs = 500)
            setupSlowBackend(wireMockServer3, delayMs = 500)

            addNodes()

            // When: Making a request
            val result = loadBalancerService.handleRequest("/test", HttpMethod.Post, body = """{"test": "all-slow"}""")

            // Then: Should fail after exhausting all retries
            assert(result is RequestResult.RequestFailed) { "Expected RequestFailed but got $result" }
            val failed = result as RequestResult.RequestFailed
            failed.error shouldContain "timeout"
        }

    @Test
    fun `should handle all backends being down`() =
        runTest {
            // Given: All backends are down
            setupDownBackend(wireMockServer1)
            setupDownBackend(wireMockServer2)
            setupDownBackend(wireMockServer3)

            addNodes()

            // When: Making a request
            val result = loadBalancerService.handleRequest("/test", HttpMethod.Post, body = """{"test": "all-down"}""")

            // Then: Should fail
            assert(result is RequestResult.RequestFailed) { "Expected RequestFailed but got $result" }
        }

    @Test
    fun `should handle intermittent backend failures`() =
        runTest {
            // Given: backend-1 fails every other request
            setupIntermittentBackend(wireMockServer1)
            setupHealthyBackend(wireMockServer2, "backend-2")
            setupHealthyBackend(wireMockServer3, "backend-3")

            addNodes()

            // When: Making 9 requests (3 per backend in round-robin)
            val results =
                (1..9).map {
                    loadBalancerService.handleRequest("/test", HttpMethod.Post, body = """{"request": $it}""")
                }

            // Then: Most requests should succeed due to retries
            val successCount = results.filterIsInstance<RequestResult.Success>().size
            assert(successCount > 6) { "Expected more than 6 successful requests, got $successCount" }
        }

    @Test
    fun `should detect unhealthy backend via health checks and remove it`() =
        runTest(timeout = 20.seconds) {
            // Given: All backends start healthy
            setupHealthyBackend(wireMockServer1, "backend-1")
            setupHealthyBackend(wireMockServer2, "backend-2")
            setupHealthyBackend(wireMockServer3, "backend-3")

            addNodes()

            // Start health monitoring
            val scope = CoroutineScope(Dispatchers.Default + Job())
            healthMonitor.start(scope)

            try {
                // Initial state: 3 nodes available
                nodeRepository.findAvailableNodes().size shouldBe 3

                // When: backend-1 goes down
                wireMockServer1.resetAll()
                setupDownBackend(wireMockServer1)

                // Wait for health checks to detect failure (give extra time for actual execution)
                // With 5s interval, need at least 3 checks = 15s, add buffer for processing
                delay(20.seconds)

                // Then: Node should be marked as unavailable after consecutive failures
                val availableNodes = nodeRepository.findAvailableNodes()
                assert(availableNodes.size < 3) { "Expected less than 3 available nodes, got ${availableNodes.size}" }

                // Verify backend-1 health endpoint was called multiple times
                wireMockServer1.verify(
                    moreThanOrExactly(3),
                    getRequestedFor(urlEqualTo("/health")),
                )
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `should recover when slow backend becomes fast again`() =
        runTest {
            // Given: backend-1 starts slow
            setupSlowBackend(wireMockServer1, delayMs = 500)
            setupHealthyBackend(wireMockServer2, "backend-2")
            setupHealthyBackend(wireMockServer3, "backend-3")

            addNodes()

            // When: First request times out on backend-1, retries succeed
            val result1 = loadBalancerService.handleRequest("/test", HttpMethod.Post, body = """{"test": "1"}""")
            assert(result1 is RequestResult.Success) { "Expected Success but got $result1" }

            // Then: backend-1 becomes fast
            wireMockServer1.resetAll()
            setupHealthyBackend(wireMockServer1, "backend-1")

            // When: Making more requests
            val results =
                (2..4).map {
                    loadBalancerService.handleRequest("/test", HttpMethod.Post, body = """{"test": "$it"}""")
                }

            // Then: All should succeed including requests to backend-1
            results.forEach { result ->
                assert(result is RequestResult.Success) { "Expected Success but got $result" }
            }
        }

    @Test
    fun `should handle varying response times across backends`() =
        runTest {
            // Given: Backends with different response times (all within timeout)
            setupBackendWithDelay(wireMockServer1, "backend-1", delayMs = 50)
            setupBackendWithDelay(wireMockServer2, "backend-2", delayMs = 100)
            setupBackendWithDelay(wireMockServer3, "backend-3", delayMs = 150)

            addNodes()

            // When: Making 9 requests
            val results =
                (1..9).map {
                    loadBalancerService.handleRequest("/test", HttpMethod.Post, body = """{"request": $it}""")
                }

            // Then: All should succeed as they're within timeout
            results.forEach { result ->
                assert(result is RequestResult.Success) { "Expected Success but got $result" }
            }

            // Verify round-robin distribution
            wireMockServer1.verify(3, postRequestedFor(urlEqualTo("/test")))
            wireMockServer2.verify(3, postRequestedFor(urlEqualTo("/test")))
            wireMockServer3.verify(3, postRequestedFor(urlEqualTo("/test")))
        }

    @Test
    fun `should handle connection refused scenario`() =
        runTest {
            // Given: backend-1 is completely stopped (connection refused)
            wireMockServer1.stop()

            setupHealthyBackend(wireMockServer2, "backend-2")
            setupHealthyBackend(wireMockServer3, "backend-3")

            addNodes()

            // When: Making requests
            val results =
                (1..6).map {
                    loadBalancerService.handleRequest("/test", HttpMethod.Post, body = """{"request": $it}""")
                }

            // Then: Should retry and succeed on healthy backends
            val successCount = results.filterIsInstance<RequestResult.Success>().size
            assert(successCount > 4) { "Expected more than 4 successful requests, got $successCount" }
        }

    // Helper methods

    private suspend fun addNodes() {
        nodeRepository.save(Node(NodeId("backend-1"), Endpoint("localhost", 8091)))
        nodeRepository.save(Node(NodeId("backend-2"), Endpoint("localhost", 8092)))
        nodeRepository.save(Node(NodeId("backend-3"), Endpoint("localhost", 8093)))
    }

    private fun setupHealthyBackend(
        server: WireMockServer,
        backendName: String,
    ) {
        server.stubFor(
            post(urlMatching("/.*"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"message": "Success from $backendName", "backend": "$backendName"}"""),
                ),
        )

        server.stubFor(
            get(urlEqualTo("/health"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"status": "healthy"}"""),
                ),
        )
    }

    private fun setupSlowBackend(
        server: WireMockServer,
        delayMs: Int,
    ) {
        server.stubFor(
            post(urlMatching("/.*"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"message": "Slow response"}""")
                        .withFixedDelay(delayMs),
                ),
        )

        server.stubFor(
            get(urlEqualTo("/health"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withFixedDelay(delayMs),
                ),
        )
    }

    private fun setupBackendWithDelay(
        server: WireMockServer,
        backendName: String,
        delayMs: Int,
    ) {
        server.stubFor(
            post(urlMatching("/.*"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"message": "Response from $backendName", "backend": "$backendName"}""")
                        .withFixedDelay(delayMs),
                ),
        )

        server.stubFor(
            get(urlEqualTo("/health"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withFixedDelay(delayMs),
                ),
        )
    }

    private fun setupDownBackend(server: WireMockServer) {
        server.stubFor(
            post(urlMatching("/.*"))
                .willReturn(
                    aResponse()
                        .withStatus(503)
                        .withBody("""{"error": "Service unavailable"}"""),
                ),
        )

        server.stubFor(
            get(urlEqualTo("/health"))
                .willReturn(
                    aResponse()
                        .withStatus(503),
                ),
        )
    }

    private fun setupIntermittentBackend(server: WireMockServer) {
        // Succeeds on scenario 1, fails on scenario 2 (alternating)
        server.stubFor(
            post(urlMatching("/.*"))
                .inScenario("Intermittent")
                .whenScenarioStateIs("Started")
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"message": "Success"}"""),
                ).willSetStateTo("Failed"),
        )

        server.stubFor(
            post(urlMatching("/.*"))
                .inScenario("Intermittent")
                .whenScenarioStateIs("Failed")
                .willReturn(
                    aResponse()
                        .withStatus(500)
                        .withBody("""{"error": "Internal server error"}"""),
                ).willSetStateTo("Started"),
        )

        server.stubFor(
            get(urlEqualTo("/health"))
                .willReturn(aResponse().withStatus(200)),
        )
    }
}
