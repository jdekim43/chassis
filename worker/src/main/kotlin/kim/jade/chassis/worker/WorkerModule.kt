package kim.jade.chassis.worker

import kim.jade.chassis.hook.hook
import kim.jade.chassis.hook.onRequestStop
import kim.jade.chassis.hook.onStarted
import kim.jade.chassis.hook.onStop
import org.koin.dsl.module

@PublishedApi
internal val workerModule = module {
    hook {
        val supervisor = WorkerSupervisor(
            application = get(),
            workers = getAll<Worker>(),
            options = getOrNull<WorkerOptions>() ?: WorkerOptions(),
        )
        onStarted("workers") { supervisor.launch() }
        onRequestStop("workers") { supervisor.requestStop() }
        onStop("workers") { supervisor.stop() }
    }
}
