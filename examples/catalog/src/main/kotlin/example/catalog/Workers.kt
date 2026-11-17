package example.catalog

import kim.jade.chassis.Application
import kim.jade.chassis.cli.RunningCommand
import kim.jade.chassis.health.HealthReport
import kim.jade.chassis.health.checkReadiness
import kim.jade.chassis.worker.Worker
import kim.jade.kotlinx.logger.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

private val logger = Logger.named("example.catalog")

// 모듈은 어떤 명령에서든 전부 올라가므로, serve에서만 돌 Worker는 스스로 가려야 한다.
private val Application.isServing: Boolean
    get() = attributes.getOrNull(RunningCommand) is ServeCommand

/** 종료가 요청되면 쉬고 있던 Worker를 바로 깨운다. 없으면 종료가 Worker의 `graceful`만큼 늦어진다. */
class StopSignal {
    private val requested = CompletableDeferred<Unit>()

    fun request() {
        requested.complete(Unit)
    }

    /** [duration]만큼 쉰다. 그 사이에 종료가 요청되면 false를 돌려준다. */
    suspend fun sleep(duration: Duration): Boolean = withTimeoutOrNull(duration) { requested.await() } == null
}

class CatalogRefresh(
    private val application: Application,
    private val store: CatalogStore,
    private val config: CatalogConfig,
) : Worker {
    private val stop = StopSignal()

    override fun onRequestStop() = stop.request()

    override suspend fun run() {
        if (!application.isServing) return
        while (stop.sleep(config.refreshInterval)) {
            val before = store.names
            val failedBefore = store.failure != null
            store.refresh()
            val failure = store.failure
            if (failure != null && !failedBefore) {
                logger.warning("Catalog source unreadable", exception = failure, meta = mapOf("source" to config.source))
            }
            if (store.names != before) logger.info("Catalog refreshed: ${store.names.size} products")
        }
    }
}

class HealthMonitor(private val application: Application, private val config: HealthConfig) : Worker {
    private val stop = StopSignal()

    override fun onRequestStop() = stop.request()

    override suspend fun run() {
        if (!application.isServing) return
        var last: String? = null
        do {
            val line = application.checkReadiness().describe()
            if (line != last) logger.info(line)
            last = line
        } while (stop.sleep(config.interval))
    }
}

private fun HealthReport.describe(): String {
    if (healthy) return "Readiness: healthy"
    val failed = checks.filterNot { it.healthy }
    val detail = if (failed.isEmpty()) status.toString() else failed.joinToString { listOfNotNull(it.name, it.message).joinToString(": ") }
    return "Readiness: unhealthy ($detail)"
}
