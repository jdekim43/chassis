package example.catalog

import com.github.ajalt.clikt.core.Context
import kim.jade.chassis.Application
import kim.jade.chassis.cli.ApplicationCommand
import kim.jade.chassis.ktor.KtorServer
import kotlinx.coroutines.awaitCancellation

class ListCommand : ApplicationCommand("list") {
    override fun help(context: Context) = "Print the product names"

    override suspend fun run(application: Application) {
        application.koin.get<CatalogStore>().names.forEach { echo(it) }
    }
}

class ServeCommand : ApplicationCommand("serve") {
    override fun help(context: Context) =
        "Serve the catalog over HTTP and keep it in step with the source file until stopped"

    override suspend fun run(application: Application) {
        val server = application.koin.get<KtorServer>()
        server.start()
        echo("Serving on ${server.addresses().joinToString()}. Press Ctrl+C to stop.")
        awaitCancellation()
    }
}
