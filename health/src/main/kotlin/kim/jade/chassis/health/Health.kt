package kim.jade.chassis.health

import kim.jade.chassis.ApplicationStatus

data class Health(val healthy: Boolean, val message: String? = null, val cause: Throwable? = null) {
    companion object {
        fun healthy(message: String? = null): Health = Health(true, message)
        fun unhealthy(message: String? = null, cause: Throwable? = null): Health = Health(false, message, cause)
    }
}

data class HealthCheckResult(
    val name: String,
    val healthy: Boolean,
    val message: String? = null,
    val cause: Throwable? = null,
)

data class HealthReport(
    val status: ApplicationStatus,
    val healthy: Boolean,
    val checks: List<HealthCheckResult>,
)
