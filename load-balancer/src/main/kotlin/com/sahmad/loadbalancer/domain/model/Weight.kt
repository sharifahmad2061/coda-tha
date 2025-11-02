package com.sahmad.loadbalancer.domain.model

/**
 * Value object representing the weight of a node in weighted load balancing.
 */
@JvmInline
value class Weight(
    val value: Int,
) {
    init {
        require(value > 0) { "Weight must be positive, got: $value" }
    }

    operator fun compareTo(other: Weight): Int = value.compareTo(other.value)

    override fun toString(): String = value.toString()
}
