package com.coda.loadbalancer.domain.model

/**
 * Value object representing a backend service endpoint.
 */
data class Endpoint(
    val host: String,
    val port: Int,
    val scheme: String = "http"
) {
    init {
        require(host.isNotBlank()) { "Host cannot be blank" }
        require(port in 1..65535) { "Port must be between 1 and 65535, got: $port" }
        require(scheme in listOf("http", "https")) { "Scheme must be http or https, got: $scheme" }
    }

    fun toUrl(): String = "$scheme://$host:$port"

    override fun toString(): String = toUrl()
}

