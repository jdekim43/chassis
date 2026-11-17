package kim.jade.chassis.config.toml.r8

import kim.jade.chassis.config.Configuration
import kim.jade.chassis.config.ConfigurationIssue
import kim.jade.chassis.config.ConfigurationResolver
import kim.jade.chassis.config.ValidatableConfiguration
import kim.jade.chassis.config.document.ConfigurationDocument
import kim.jade.chassis.config.resolve
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.Serializable

@Serializable
data class SmokeEndpoint(val host: String = "localhost", val port: Int = 80)

@Configuration("smoke")
data class SmokeConfig(
    val name: String,
    val port: Int = 8080,
    val primary: SmokeEndpoint = SmokeEndpoint(),
    val replicas: Map<String, SmokeEndpoint> = emptyMap(),
    val timeout: Duration = 5.seconds,
) : ValidatableConfiguration {
    override fun validate(): List<ConfigurationIssue> =
        if (port in 1..65535) emptyList() else listOf(ConfigurationIssue("port", "OUT_OF_RANGE"))
}

@Configuration("named")
data class SmokeNamedConfig(val greeting: String) {
    companion object Defaults
}

fun main(args: Array<String>) {
    val resolver = ConfigurationResolver().apply { add(ConfigurationDocument.load(File(args[0]))) }
    val config = resolver.resolve<SmokeConfig>()
    val named = resolver.resolve<SmokeNamedConfig>()
    val replicas = config.replicas.entries.joinToString(",") { "${it.key}=${it.value.host}:${it.value.port}" }
    println(
        "name=${config.name} port=${config.port} primary=${config.primary.host}:${config.primary.port} " +
            "replicas=$replicas timeout=${config.timeout} greeting=${named.greeting}",
    )
}
