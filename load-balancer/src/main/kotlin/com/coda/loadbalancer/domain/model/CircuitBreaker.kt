package com.coda.loadbalancer.domain.model

import kotlin.time.Duration

/**
 * Circuit breaker states following the Circuit Breaker pattern.
 */
enum class CircuitBreakerState {
    CLOSED,      // Normal operation, requests are allowed
    OPEN,        // Too many failures, requests are blocked
    HALF_OPEN    // Testing if service recovered, limited requests allowed
}

/**
 * Configuration for circuit breaker behavior.
 */
data class CircuitBreakerConfig(
    val failureThreshold: Int = 5,
    val timeout: Duration = Duration.parse("30s"),
    val halfOpenMaxAttempts: Int = 3
) {
    init {
        require(failureThreshold > 0) { "Failure threshold must be positive" }
        require(halfOpenMaxAttempts > 0) { "Half-open max attempts must be positive" }
    }
}

/**
 * Entity representing a circuit breaker for a node.
 * Prevents cascading failures by tracking failures and blocking requests when threshold is exceeded.
 */
data class CircuitBreaker(
    val config: CircuitBreakerConfig = CircuitBreakerConfig(),
    private var state: CircuitBreakerState = CircuitBreakerState.CLOSED,
    private var failureCount: Int = 0,
    private var lastFailureTime: Long? = null,
    private var halfOpenAttempts: Int = 0
) {
    /**
     * Check if a request can be attempted.
     */
    fun canAttemptRequest(): Boolean {
        when (state) {
            CircuitBreakerState.CLOSED -> return true
            CircuitBreakerState.HALF_OPEN -> return halfOpenAttempts < config.halfOpenMaxAttempts
            CircuitBreakerState.OPEN -> {
                val currentTime = System.currentTimeMillis()
                val timeSinceLastFailure = lastFailureTime?.let { currentTime - it } ?: 0
                if (timeSinceLastFailure >= config.timeout.inWholeMilliseconds) {
                    transitionToHalfOpen()
                    return true
                }
                return false
            }
        }
    }

    /**
     * Record a successful request.
     */
    fun recordSuccess() {
        when (state) {
            CircuitBreakerState.HALF_OPEN -> {
                halfOpenAttempts++
                if (halfOpenAttempts >= config.halfOpenMaxAttempts) {
                    transitionToClosed()
                }
            }
            CircuitBreakerState.CLOSED -> {
                // Reset failure count on success
                if (failureCount > 0) {
                    failureCount = 0
                }
            }
            CircuitBreakerState.OPEN -> {
                // Should not happen, but handle gracefully
            }
        }
    }

    /**
     * Record a failed request.
     */
    fun recordFailure() {
        failureCount++
        lastFailureTime = System.currentTimeMillis()

        when (state) {
            CircuitBreakerState.CLOSED -> {
                if (failureCount >= config.failureThreshold) {
                    transitionToOpen()
                }
            }
            CircuitBreakerState.HALF_OPEN -> {
                // Any failure in half-open state reopens the circuit
                transitionToOpen()
            }
            CircuitBreakerState.OPEN -> {
                // Already open, just update the timestamp
            }
        }
    }

    /**
     * Get the current state of the circuit breaker.
     */
    fun getState(): CircuitBreakerState = state

    /**
     * Get the current failure count.
     */
    fun getFailureCount(): Int = failureCount

    /**
     * Force reset the circuit breaker to closed state.
     */
    fun reset() {
        state = CircuitBreakerState.CLOSED
        failureCount = 0
        lastFailureTime = null
        halfOpenAttempts = 0
    }

    private fun transitionToOpen() {
        state = CircuitBreakerState.OPEN
        halfOpenAttempts = 0
    }

    private fun transitionToHalfOpen() {
        state = CircuitBreakerState.HALF_OPEN
        halfOpenAttempts = 0
    }

    private fun transitionToClosed() {
        state = CircuitBreakerState.CLOSED
        failureCount = 0
        halfOpenAttempts = 0
    }
}

