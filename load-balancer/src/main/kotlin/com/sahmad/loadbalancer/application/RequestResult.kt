package com.sahmad.loadbalancer.application

import kotlin.time.Duration

/**
 * Result of handling a request in the load balancer.
 * Represents the outcome of routing a request to a backend node.
 */
sealed interface RequestResult {
    /**
     * Request was successfully forwarded and processed by a backend node.
     */
    data class Success(
        val nodeId: String,
        val statusCode: Int,
        val latency: Duration,
        val responseBody: String,
    ) : RequestResult

    /**
     * Request failed during forwarding or processing.
     */
    data class RequestFailed(
        val error: String,
    ) : RequestResult

    /**
     * No backend nodes are available to handle the request.
     */
    data object NoAvailableNodes : RequestResult

    /**
     * The load balancing strategy failed to select a node.
     */
    data object SelectionFailed : RequestResult
}
