package kim.jade.chassis.health

import kim.jade.chassis.Application
import kim.jade.chassis.hook.hook
import org.koin.core.module.Module
import org.koin.core.scope.Scope

fun Module.liveness(name: String, check: suspend Scope.() -> Health) {
    require(name.isNotBlank()) { "Health check name must not be blank" }
    hook { get<Application>().healthChecks.addLiveness(HealthCheck(name, this, check)) }
}

fun Module.readiness(name: String, check: suspend Scope.() -> Health) {
    require(name.isNotBlank()) { "Health check name must not be blank" }
    hook { get<Application>().healthChecks.addReadiness(HealthCheck(name, this, check)) }
}
