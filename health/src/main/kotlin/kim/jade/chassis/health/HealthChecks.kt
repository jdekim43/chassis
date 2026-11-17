package kim.jade.chassis.health

import kim.jade.chassis.Application
import kim.jade.chassis.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.koin.core.scope.Scope
import java.util.concurrent.CopyOnWriteArrayList

private val HealthChecksKey = AttributeKey<HealthChecks>("chassis.health.checks")

internal val Application.healthChecks: HealthChecks
    get() = attributes.getOrPut(HealthChecksKey) { HealthChecks() }

internal class HealthChecks {
    private val livenessChecks = CopyOnWriteArrayList<HealthCheck>()
    private val readinessChecks = CopyOnWriteArrayList<HealthCheck>()

    val liveness: List<HealthCheck> get() = livenessChecks.toList()
    val readiness: List<HealthCheck> get() = readinessChecks.toList()

    fun addLiveness(check: HealthCheck) = add(livenessChecks, "liveness", check)

    fun addReadiness(check: HealthCheck) = add(readinessChecks, "readiness", check)

    @Synchronized
    private fun add(checks: MutableList<HealthCheck>, kind: String, check: HealthCheck) {
        require(checks.none { it.name == check.name }) { "Duplicate $kind check '${check.name}'" }
        checks += check
    }
}

internal class HealthCheck(
    val name: String,
    private val scope: Scope,
    private val check: suspend Scope.() -> Health,
) {
    suspend fun run(): HealthCheckResult = try {
        val health = scope.check()
        HealthCheckResult(name, health.healthy, health.message, health.cause)
    } catch (cancelled: CancellationException) {
        currentCoroutineContext().ensureActive()
        HealthCheckResult(name, healthy = false, cause = cancelled)
    } catch (failure: Exception) {
        HealthCheckResult(name, healthy = false, cause = failure)
    }
}
