package kim.jade.chassis.cli

import kim.jade.chassis.hook.hook
import kim.jade.chassis.hook.onRequestStop
import kim.jade.chassis.hook.onStarted
import org.koin.core.module.Module
import org.koin.dsl.module

internal fun cliModule(): Module = module {
    hook {
        val runner = CommandRunner(get())
        onStarted("command") { runner.launch() }
        onRequestStop("command") { runner.cancelAndJoin() }
    }
}
