package com.coda.loadbalancer.domain.event

import com.coda.loadbalancer.domain.model.CircuitBreakerState
import com.coda.loadbalancer.domain.model.HealthStatus
import com.coda.loadbalancer.domain.model.NodeId
import java.time.Instant
import kotlin.time.Duration

/**
 * Base interface for all domain events.
 */
sealed interface DomainEvent {
    val occurredAt: Instant
    val aggregateId: String
}

/**
 * Event published when a node's health status changes.
 */
data class NodeHealthChangedEvent(
    val nodeId: NodeId,
    val previousStatus: HealthStatus,
    val newStatus: HealthStatus,
    val reason: String,
    override val occurredAt: Instant = Instant.now(),
    override val aggregateId: String = nodeId.value,
) : DomainEvent {
    override fun toString(): String = "NodeHealthChanged(node=$nodeId, $previousStatus -> $newStatus, reason='$reason')"
}

/**
 * Event published when a circuit breaker opens.
 */
data class CircuitBreakerOpenedEvent(
    val nodeId: NodeId,
    val failureCount: Int,
    val threshold: Int,
    override val occurredAt: Instant = Instant.now(),
    override val aggregateId: String = nodeId.value,
) : DomainEvent {
    override fun toString(): String = "CircuitBreakerOpened(node=$nodeId, failures=$failureCount, threshold=$threshold)"
}

/**
 * Event published when a circuit breaker closes.
 */
data class CircuitBreakerClosedEvent(
    val nodeId: NodeId,
    val previousState: CircuitBreakerState,
    override val occurredAt: Instant = Instant.now(),
    override val aggregateId: String = nodeId.value,
) : DomainEvent {
    override fun toString(): String = "CircuitBreakerClosed(node=$nodeId, from=$previousState)"
}

/**
 * Event published when a request fails.
 */
data class RequestFailedEvent(
    val nodeId: NodeId,
    val error: String,
    val latency: Duration,
    override val occurredAt: Instant = Instant.now(),
    override val aggregateId: String = nodeId.value,
) : DomainEvent {
    override fun toString(): String = "RequestFailed(node=$nodeId, error='$error', latency=$latency)"
}

/**
 * Event published when a request succeeds.
 */
data class RequestSucceededEvent(
    val nodeId: NodeId,
    val latency: Duration,
    override val occurredAt: Instant = Instant.now(),
    override val aggregateId: String = nodeId.value,
) : DomainEvent {
    override fun toString(): String = "RequestSucceeded(node=$nodeId, latency=$latency)"
}

/**
 * Event published when a node is added to the pool.
 */
data class NodeAddedEvent(
    val nodeId: NodeId,
    override val occurredAt: Instant = Instant.now(),
    override val aggregateId: String = nodeId.value,
) : DomainEvent

/**
 * Event published when a node is removed from the pool.
 */
data class NodeRemovedEvent(
    val nodeId: NodeId,
    val reason: String,
    override val occurredAt: Instant = Instant.now(),
    override val aggregateId: String = nodeId.value,
) : DomainEvent
