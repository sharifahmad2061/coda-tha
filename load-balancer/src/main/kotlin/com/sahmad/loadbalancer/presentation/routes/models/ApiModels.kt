package com.sahmad.loadbalancer.presentation.routes.models

import kotlinx.serialization.Serializable

@Serializable
data class NodeResponse(
    val id: String,
    val endpoint: String,
    val health: String,
)

@Serializable
data class NodeListResponse(
    val nodes: List<NodeResponse>,
)

@Serializable
data class NodeDetailResponse(
    val id: String,
    val endpoint: String,
    val health: String,
    val available: Boolean,
)

@Serializable
data class NodeMetricsSummary(
    val total: Int,
    val available: Int,
    val unavailable: Int,
)

@Serializable
data class MetricsResponse(
    val nodes: NodeMetricsSummary,
    val nodeDetails: List<NodeDetailResponse>,
)

@Serializable
data class AddNodeRequest(
    val id: String,
    val host: String,
    val port: Int,
)

@Serializable
data class BackendApiResponse(
    val message: String,
    val path: String,
    val receivedBody: String,
    val delayApplied: Long,
)

@Serializable
data class LoadBalancerResponse(
    val message: String,
    val path: String,
    val receivedBody: String,
    val processedBy: String,
)

@Serializable
data class MessageResponse(
    val message: String,
)

@Serializable
data class ErrorResponse(
    val error: String,
)
