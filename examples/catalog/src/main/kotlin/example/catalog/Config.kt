package example.catalog

import kim.jade.chassis.config.Configuration
import kim.jade.chassis.config.ConfigurationIssue
import kim.jade.chassis.config.ValidatableConfiguration
import kotlinx.serialization.SerialName
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Configuration("catalog")
data class CatalogConfig(
    val source: String,
    @SerialName("refresh-interval") val refreshInterval: Duration = 30.seconds,
) : ValidatableConfiguration {
    override fun validate(): List<ConfigurationIssue> = buildList {
        if (source.isBlank()) add(ConfigurationIssue("source", "BLANK"))
        if (!refreshInterval.isPositive()) add(ConfigurationIssue("refresh-interval", "NOT_POSITIVE"))
    }
}

@Configuration("health")
data class HealthConfig(val interval: Duration = 10.seconds) : ValidatableConfiguration {
    override fun validate(): List<ConfigurationIssue> =
        if (interval.isPositive()) emptyList() else listOf(ConfigurationIssue("interval", "NOT_POSITIVE"))
}

@Configuration("server")
data class ServerConfig(val host: String = "127.0.0.1", val port: Int = 8080) : ValidatableConfiguration {
    override fun validate(): List<ConfigurationIssue> =
        if (port in 0..65535) emptyList() else listOf(ConfigurationIssue("port", "OUT_OF_RANGE"))
}
