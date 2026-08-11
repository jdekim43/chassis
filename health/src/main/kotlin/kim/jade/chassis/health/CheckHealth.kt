package kim.jade.chassis.health

import kim.jade.chassis.Application
import kim.jade.chassis.ApplicationStatus

suspend fun Application.checkLiveness(): HealthReport {
    val before = status
    if (before != ApplicationStatus.ACTIVE) return HealthReport(before, before.isStartingOrStopping(), emptyList())

    val results = healthChecks.liveness.map { it.run() }

    // 점검하는 동안 앱이 종료 절차에 들어갔으면 점검 결과가 아니라 상태로 답한다.
    val after = status
    val healthy = if (after == ApplicationStatus.ACTIVE) results.all { it.healthy } else after.isStartingOrStopping()
    return HealthReport(after, healthy, results)
}

suspend fun Application.checkReadiness(): HealthReport {
    val before = status
    if (before != ApplicationStatus.ACTIVE) return HealthReport(before, false, emptyList())

    val results = healthChecks.readiness.map { it.run() }

    val after = status
    val healthy = after == ApplicationStatus.ACTIVE && results.all { it.healthy }
    return HealthReport(after, healthy, results)
}

private fun ApplicationStatus.isStartingOrStopping(): Boolean =
    this == ApplicationStatus.STARTING || this == ApplicationStatus.STOPPING
