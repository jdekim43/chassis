package kim.jade.chassis

import kim.jade.chassis.hook.onRelease
import kim.jade.chassis.hook.onStart
import org.koin.dsl.module
import java.io.File
import kotlin.system.exitProcess

suspend fun main(args: Array<String>) {
    val marker = File(args[1])
    runApplication(module {
        onStart { marker.appendText("ready\n") }
        onRelease { marker.appendText("release\n") }
    })
    exitProcess(0)
}
