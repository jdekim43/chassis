package kim.jade.chassis

import kim.jade.chassis.hook.onInitialize
import kim.jade.chassis.hook.onRelease
import kim.jade.chassis.hook.onStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.io.TempDir
import org.koin.dsl.module
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunApplicationProcessTest {
    @TempDir lateinit var directory: File

    @Test fun `SIGTERM releases an active application once`() = terminate("active")
    @Test fun `SIGTERM during initialization cancels startup and releases once`() = terminate("initializing")

    @Test fun `SIGTERM ends a suspend main that exits the process after runApplication`() =
        terminate("active", "kim.jade.chassis.SuspendMainFixtureKt")

    @Test fun `SIGINT ends a suspend main that exits the process after runApplication`() {
        assumeFalse(sigintIsIgnored(), "SIGINT is ignored here, inherited from the way Gradle was started")
        terminate("active", "kim.jade.chassis.SuspendMainFixtureKt", signal = "INT")
    }

    // 백그라운드로 띄운 Gradle은 SIGINT 무시를 자식에게 물려주고, 그러면 JVM은 SIGINT에 종료하지 않는다.
    private fun sigintIsIgnored(): Boolean {
        val probe = ProcessBuilder("sleep", "30").start()
        try {
            ProcessBuilder("kill", "-INT", probe.pid().toString()).start().waitFor()
            return !probe.waitFor(2, TimeUnit.SECONDS)
        } finally {
            probe.destroyForcibly()
        }
    }

    private fun terminate(
        mode: String,
        mainClass: String = RunApplicationFixture::class.java.name,
        signal: String = "TERM",
    ) {
        val marker = File(directory, "$mode.marker")
        val error = File(directory, "$mode.error")
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path,
            "-cp", System.getProperty("chassis.test.classpath"),
            mainClass, mode, marker.path,
        ).redirectOutput(File(directory, "$mode.output")).redirectError(error).start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!marker.exists() || !marker.readText().contains("ready")) {
                check(process.isAlive) { error.readText() }
                check(System.nanoTime() < deadline) { "Application never became ready" }
                Thread.sleep(10)
            }
            if (signal == "TERM") process.destroy()
            else assertEquals(0, ProcessBuilder("kill", "-$signal", process.pid().toString()).start().waitFor())
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Shutdown timed out")
            assertEquals(listOf("ready", "release"), marker.readLines())
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
        }
    }
}

object RunApplicationFixture {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val marker = File(args[1])
        runApplication(module {
            onInitialize {
                if (args[0] == "initializing") { marker.appendText("ready\n"); awaitCancellation() }
            }
            onStart { marker.appendText("ready\n") }
            onRelease { marker.appendText("release\n") }
        })
    }
}
