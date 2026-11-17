package kim.jade.chassis.config

import kim.jade.chassis.Application
import kim.jade.chassis.AttributeKey

private val ConfigurationsKey = AttributeKey<ConfigurationResolver>("chassis.config.resolver")

val Application.configurations: ConfigurationResolver
    get() = attributes.getOrPut(ConfigurationsKey) { ConfigurationResolver() }
