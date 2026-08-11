package example.catalog

import com.github.ajalt.clikt.command.CoreSuspendingCliktCommand
import kim.jade.chassis.Application
import kim.jade.chassis.cli.RunningCommand
import kim.jade.chassis.worker.Worker
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WorkersTest {
    @TempDir lateinit var directory: File

    private fun workers(application: Application): List<Worker> {
        val products = File(directory, "products.txt").apply { writeText("Coffee") }
        return listOf(
            CatalogRefresh(application, CatalogStore(ProductSource(products)), CatalogConfig(products.path, 1.milliseconds)),
            HealthMonitor(application, HealthConfig(1.milliseconds)),
        )
    }

    private suspend fun withApplication(command: CoreSuspendingCliktCommand?, block: suspend (Application) -> Unit) {
        val application = Application()
        if (command != null) application.attributes[RunningCommand] = command
        try {
            block(application)
        } finally {
            application.stop()
        }
    }

    @Test fun `outside serve the workers return at once`() = runBlocking {
        for (command in listOf(null, ListCommand())) {
            withApplication(command) { application ->
                for (worker in workers(application)) withTimeout(5.seconds) { worker.run() }
            }
        }
    }

    @Test fun `under serve the workers keep running`() = runBlocking {
        withApplication(ServeCommand()) { serving ->
            for (worker in workers(serving)) assertNull(withTimeoutOrNull(200.milliseconds) { worker.run() })
        }
    }
}
