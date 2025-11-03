package com.sahmad.loadbalancer.infrastructure.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for the load balancer application.
 * Loads configuration from application.conf file.
 */
object LoadBalancerConfig {
    private val config: Config = ConfigFactory.load()

    object Server {
        val host: String = config.getString("loadbalancer.server.host")
        val port: Int = config.getInt("loadbalancer.server.port")
    }

    object Request {
        val timeout: Duration = parseDuration(config.getString("loadbalancer.request.timeout"))
        val retries: Int = config.getInt("loadbalancer.request.retries")
        val maxAttempts: Int = config.getInt("loadbalancer.request.maxAttempts")
    }

    object HealthCheck {
        val enabled: Boolean = config.getBoolean("loadbalancer.healthCheck.enabled")
        val interval: Duration = parseDuration(config.getString("loadbalancer.healthCheck.interval"))
        val timeout: Duration = parseDuration(config.getString("loadbalancer.healthCheck.timeout"))
        val path: String = config.getString("loadbalancer.healthCheck.path")
        val unhealthyThreshold: Int = config.getInt("loadbalancer.healthCheck.unhealthyThreshold")
        val healthyThreshold: Int = config.getInt("loadbalancer.healthCheck.healthyThreshold")
    }

    /**
     * Parse duration string (e.g., "300ms", "5s", "1m") to Kotlin Duration.
     */
    private fun parseDuration(durationStr: String): Duration {
        val pattern = """(\d+)(ms|s|m|h)""".toRegex()
        val match =
            pattern.matchEntire(durationStr)
                ?: throw IllegalArgumentException("Invalid duration format: $durationStr")

        val (value, unit) = match.destructured
        val amount = value.toLong()

        return when (unit) {
            "ms" -> amount.milliseconds
            "s" -> amount.seconds
            "m" -> (amount * 60).seconds
            "h" -> (amount * 3600).seconds
            else -> throw IllegalArgumentException("Unknown time unit: $unit")
        }
    }
}
