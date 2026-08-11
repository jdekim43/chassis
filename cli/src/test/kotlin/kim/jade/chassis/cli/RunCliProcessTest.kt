package kim.jade.chassis.cli

import com.github.ajalt.clikt.command.CoreSuspendingCliktCommand
import kim.jade.chassis.hook.onRelease
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import org.koin.dsl.module
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunCliProcessTest {
    @TempDir lateinit var directory: File

    @Test fun `SIGTERM cancels a long running command and releases once`() {
        val marker = File(directory, "serve.marker")
        val error = File(directory, "serve.error")
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path,
            "-cp", System.getProperty("chassis.test.classpath"),
            RunCliFixture::class.java.name, marker.path,
        ).redirectOutput(File(directory, "serve.output")).redirectError(error).start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!marker.exists() || !marker.readText().contains("serving")) {
                check(process.isAlive) { error.readText() }
                check(System.nanoTime() < deadline) { "Command never started" }
                Thread.sleep(10)
            }
            process.destroy()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Shutdown timed out")
            assertEquals(listOf("serving", "cancelled", "release"), marker.readLines())
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
        }
    }
}

object RunCliFixture {
    private class Serve(val marker: File) : CoreSuspendingCliktCommand("serve") {
        override suspend fun run() {
            marker.appendText("serving\n")
            try { awaitCancellation() } finally { marker.appendText("cancelled\n") }
        }
    }

    @JvmStatic fun main(args: Array<String>): Unit = runBlocking {
        val marker = File(args[0])
        val code = runCli("tool", arrayOf("serve"), listOf(Serve(marker)), module {
            onRelease { marker.appendText("release\n") }
        })
        exitProcess(code)
    }
}
