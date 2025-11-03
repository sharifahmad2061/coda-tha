package com.sahmad.loadbalancer.infrastructure.config

import com.sahmad.loadbalancer.domain.model.Endpoint
import com.sahmad.loadbalancer.domain.model.Node
import com.sahmad.loadbalancer.domain.model.NodeId
import com.sahmad.loadbalancer.infrastructure.repository.InMemoryNodeRepository
import com.typesafe.config.ConfigFactory
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Service responsible for initializing backend nodes from configuration or environment variables.
 * Supports both Docker deployment (BACKEND_NODES env var) and local development (application.conf).
 */
object NodeInitializer {
    /**
     * Initialize nodes from environment variable or use configuration file.
     *
     * Environment variable format: BACKEND_NODES=backend-1:8080,backend-2:8080,backend-3:8080
     *
     * @param nodeRepository Repository to save initialized nodes
     */
    suspend fun initializeNodes(nodeRepository: InMemoryNodeRepository) {
        val backendNodesEnv = System.getenv("BACKEND_NODES")
        val nodes =
            if (!backendNodesEnv.isNullOrBlank()) {
                parseNodesFromEnvironment(backendNodesEnv)
            } else {
                getNodesFromConfig()
            }

        nodes.forEach { nodeRepository.save(it) }
        logger.info {
            "Initialized ${nodes.size} nodes: ${nodes.map { "${it.id.value} -> ${it.endpoint.host}:${it.endpoint.port}" }}"
        }
    }

    /**
     * Parse backend nodes from environment variable.
     * Format: host1:port1,host2:port2,host3:port3
     */
    private fun parseNodesFromEnvironment(envValue: String): List<Node> =
        envValue.split(",").mapIndexed { index, nodeConfig ->
            val (host, port) = nodeConfig.trim().split(":")
            Node(
                id = NodeId("node-${index + 1}"),
                endpoint = Endpoint(host, port.toInt()),
            )
        }

    /**
     * Get nodes from application.conf file.
     */
    private fun getNodesFromConfig(): List<Node> {
        val config = ConfigFactory.load()
        val nodesConfig = config.getConfigList("loadbalancer.nodes")

        return nodesConfig.map { nodeConfig ->
            Node(
                id = NodeId(nodeConfig.getString("id")),
                endpoint =
                    Endpoint(
                        host = nodeConfig.getString("host"),
                        port = nodeConfig.getInt("port"),
                    ),
            )
        }
    }
}
