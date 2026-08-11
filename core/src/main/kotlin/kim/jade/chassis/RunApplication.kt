package kim.jade.chassis

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.koin.core.module.Module

suspend fun runApplication(vararg modules: Module) = runApplication(Application(*modules))

suspend fun runApplication(application: Application) {
    val hook = Thread({
        application.requestStop(reason = "JVM shutdown")
        runBlocking { runCatching { application.awaitTermination() } }
    }, "chassis-shutdown")
    application.requestStart()
    Runtime.getRuntime().addShutdownHook(hook)
    try {
        application.awaitTermination()
    } catch (cancelled: CancellationException) {
        application.requestStop(reason = "caller cancelled")
        withContext(NonCancellable) { runCatching { application.awaitTermination() } }
        throw cancelled
    } finally {
        try {
            Runtime.getRuntime().removeShutdownHook(hook)
        } catch (_: IllegalStateException) {
            // JVM이 이미 종료 중이다. hook이 종료 절차가 끝나길 기다린다.
        }
    }
}
