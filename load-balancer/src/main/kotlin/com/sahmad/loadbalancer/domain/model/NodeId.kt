package com.sahmad.loadbalancer.domain.model

/**
 * Value object representing a unique identifier for a Node.
 */
@JvmInline
value class NodeId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "NodeId cannot be blank" }
    }

    override fun toString(): String = value
}
