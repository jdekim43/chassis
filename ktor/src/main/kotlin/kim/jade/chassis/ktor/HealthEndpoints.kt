package kim.jade.chassis.ktor

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kim.jade.chassis.Application
import kim.jade.chassis.health.HealthReport
import kim.jade.chassis.health.checkLiveness
import kim.jade.chassis.health.checkReadiness
import org.koin.core.module.Module

fun Module.healthEndpoints() = ktor { server ->
    val application = get<Application>()
    server.routing {
        get("/health/live") { call.respondHealth(application) { checkLiveness() } }
        get("/health/ready") { call.respondHealth(application) { checkReadiness() } }
    }
}

private suspend fun ApplicationCall.respondHealth(
    application: Application,
    check: suspend Application.() -> HealthReport,
) {
    val report = try {
        application.check()
    } catch (failure: Error) {
        respondText("${application.status} unhealthy\n", ContentType.Text.Plain, HttpStatusCode.ServiceUnavailable)
        throw failure
    }
    val status = if (report.healthy) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
    respondText(report.text(), ContentType.Text.Plain, status)
}

private val lineBreak = Regex("\\r\\n|\\r|\\n")

private fun HealthReport.text(): String = buildString {
    append(status).append(' ').append(word(healthy)).append('\n')
    for (check in checks) {
        append(check.name).append(": ").append(word(check.healthy))
        check.message?.let { append(" - ").append(it.replace(lineBreak, " ")) }
        append('\n')
    }
}

private fun word(healthy: Boolean) = if (healthy) "healthy" else "unhealthy"
