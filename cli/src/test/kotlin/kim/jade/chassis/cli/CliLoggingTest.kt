package kim.jade.chassis.cli

import kim.jade.chassis.cli.testing.captureCli
import kim.jade.kotlinx.logger.LogLevel
import kim.jade.kotlinx.logger.LogRecordData
import kim.jade.kotlinx.logger.Logger
import kim.jade.kotlinx.logger.pipeline.LogPipeline
import kim.jade.kotlinx.logger.pipeline.LoggerNameShortener
import kim.jade.kotlinx.logger.pipeline.SinkPipe
import kim.jade.kotlinx.logger.pipeline.StdOutSink
import kim.jade.kotlinx.logger.pipeline.TextFormatter
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CliLoggingTest {
    private val logger = Logger.named("test.cli.logging")
    private val dir = createTempDirectory("cli-logging")
    private val file = dir.resolve("out.log")

    @AfterTest fun cleanup() { dir.toFile().deleteRecursively() }

    private inline fun withFreshPipeline(block: (LogPipeline) -> Unit) {
        val original = Logger.defaultPipeline
        val pipeline = LogPipeline().install(LoggerNameShortener()).install(TextFormatter()).install(StdOutSink())
        Logger.defaultPipeline = pipeline
        try {
            block(pipeline)
        } finally {
            Logger.defaultPipeline = original
        }
    }

    @Test fun `without a target only errors are printed, to stderr`() = runTest {
        withFreshPipeline {
            val result = captureCli {
                withCliLogging(null) {
                    logger.info("quiet")
                    logger.warning("still quiet")
                    logger.error("loud", exception = IllegalStateException("cause-marker"))
                }
                0
            }
            assertEquals("", result.out)
            assertFalse("quiet" in result.err)
            assertContains(result.err, "loud")
            assertContains(result.err, "java.lang.IllegalStateException: cause-marker")
        }
    }

    @Test fun `a console target gets every log once`() = runTest {
        suspend fun logTo(target: String) = captureCli {
            withCliLogging(target) {
                logger.info("first")
                logger.error("second")
            }
            0
        }
        val stdout = logTo("stdout")
        assertEquals(1, Regex("first").findAll(stdout.out).count())
        assertEquals(1, Regex("second").findAll(stdout.out).count())
        assertEquals("", stdout.err)
        val stderr = logTo("stderr")
        assertEquals(1, Regex("first").findAll(stderr.err).count())
        assertEquals(1, Regex("second").findAll(stderr.err).count())
        assertEquals("", stderr.out)
    }

    @Test fun `stdout logs keep their stack traces on stderr like the console sink`() = runTest {
        withFreshPipeline {
            val result = captureCli {
                withCliLogging("stdout") {
                    logger.error("boom", exception = IllegalStateException("cause-marker"))
                }
                0
            }
            assertContains(result.out, "boom")
            assertFalse("\tat " in result.out)
            assertContains(result.err, "java.lang.IllegalStateException: cause-marker")
            assertContains(result.err, "\tat ")
        }
    }

    @Test fun `stderr gets stack traces when the console sink printed them`() = runTest {
        withFreshPipeline {
            val result = captureCli {
                withCliLogging("stderr") {
                    logger.error("boom", exception = IllegalStateException("cause-marker"))
                }
                0
            }
            assertContains(result.err, "java.lang.IllegalStateException: cause-marker")
            assertContains(result.err, "\tat ")
        }
    }

    @Test fun `console stack traces follow the console sink that was replaced`() = runTest {
        withFreshPipeline { pipeline ->
            pipeline[StdOutSink].single().printStackTrace = false
            for (target in listOf("stderr", null)) {
                val result = captureCli {
                    withCliLogging(target) {
                        logger.error("boom", exception = IllegalStateException("cause-marker"))
                    }
                    0
                }
                assertContains(result.err, "boom", message = "$target")
                assertFalse("\tat " in result.err, "$target")
            }
        }
    }

    @Test fun `a file target gets every log, appends, and keeps the console quiet`() = runTest {
        val result = captureCli {
            repeat(2) {
                withCliLogging(file.toString()) {
                    logger.info("first")
                    logger.error("second")
                }
            }
            0
        }
        val text = file.readText()
        assertEquals(2, Regex("first").findAll(text).count())
        assertEquals(2, Regex("second").findAll(text).count())
        assertEquals("", result.out)
        assertEquals("", result.err)
    }

    @Test fun `a file log is on disk before restore`() {
        withCliLogging(file.toString()) {
            logger.info("flushed")
            assertContains(file.readText(), "flushed")
        }
    }

    @Test fun `an exception's stack trace is written to the file`() {
        withCliLogging(file.toString()) {
            logger.error("boom", exception = IllegalStateException("cause-marker"))
        }
        val text = file.readText()
        assertContains(text, "java.lang.IllegalStateException: cause-marker")
        assertContains(text, "\tat ")
    }

    @Test fun `a path that cannot be opened throws and changes nothing`() {
        val before = consoleSinkState()
        assertFailsWith<IOException> { CliLogging.install(dir.resolve("missing/out.log").toString()) }
        assertFailsWith<IOException> { CliLogging.install(dir.toString()) }
        assertFailsWith<IOException> { CliLogging.install("") }
        assertEquals(before, consoleSinkState())
        assertFalse(dir.resolve("missing").exists())
        withCliLogging(null) { }
    }

    @Test fun `restore puts the console sink back where it was`() {
        val before = consoleSinkState()
        withCliLogging(null) {
            val during = consoleSinkState()
            assertTrue(during.cliSinkInstalled)
            assertEquals(null, during.sink)
            assertEquals(before.index, Logger.defaultPipeline.installIndexOf(CliLogSink))
        }
        assertEquals(before, consoleSinkState())
    }

    @Test fun `restore puts the console sink back after the pipeline lost a pipe`() = withFreshPipeline { pipeline ->
        withCliLogging(null) { pipeline.uninstall(LoggerNameShortener) }
        assertEquals(0, pipeline.installIndexOf(TextFormatter))
        assertEquals(1, pipeline.installIndexOf(StdOutSink))
        assertFalse(pipeline.isInstalled(CliLogSink))
    }

    @Test fun `restore puts the console sink back after a pipe was added in front`() = withFreshPipeline { pipeline ->
        withCliLogging(null) { pipeline.install(SinkPipe {}, 0) }
        assertEquals(0, pipeline.installIndexOf(SinkPipe))
        assertEquals(2, pipeline.installIndexOf(TextFormatter))
        assertEquals(3, pipeline.installIndexOf(StdOutSink))
    }

    @Test fun `restore puts the console sink back after the pipeline was cleared`() = withFreshPipeline { pipeline ->
        val console = pipeline[StdOutSink].single()
        withCliLogging(null) { pipeline.clear() }
        assertSame(console, pipeline[StdOutSink].single())
    }

    @Test fun `restore keeps a console sink that was installed during the run`() = withFreshPipeline { pipeline ->
        val own = StdOutSink(useStdErr = true)
        withCliLogging(null) { pipeline.install(own) }
        assertSame(own, pipeline[StdOutSink].single())
        assertFalse(pipeline.isInstalled(CliLogSink))
    }

    @Test fun `without a console sink and without a target the pipeline is left alone`() {
        val original = Logger.defaultPipeline
        Logger.defaultPipeline = LogPipeline().install(TextFormatter())
        try {
            withCliLogging(null) { assertFalse(Logger.defaultPipeline.isInstalled(CliLogSink)) }
        } finally {
            Logger.defaultPipeline = original
        }
    }

    @Test fun `without a console sink the CLI sink goes last and restore only removes it`() {
        val original = Logger.defaultPipeline
        Logger.defaultPipeline = LogPipeline().install(TextFormatter())
        try {
            withCliLogging(file.toString()) {
                assertEquals(1, Logger.defaultPipeline.installIndexOf(CliLogSink))
                logger.info("custom pipeline")
            }
            assertFalse(Logger.defaultPipeline.isInstalled(CliLogSink))
            assertFalse(Logger.defaultPipeline.isInstalled(StdOutSink))
            assertContains(file.readText(), "custom pipeline")
        } finally {
            Logger.defaultPipeline = original
        }
    }

    @Test fun `a second install before restore is rejected and leaves the first in place`() {
        val before = consoleSinkState()
        withCliLogging(null) {
            assertFailsWith<IllegalStateException> { CliLogging.install(null) }
            assertTrue(consoleSinkState().cliSinkInstalled)
        }
        assertEquals(before, consoleSinkState())
    }

    @Test fun `a record that arrives after close is dropped`() {
        val written = mutableListOf<String>()
        val sink = CliLogSink { written += it.body }
        sink.apply(LogRecordData("test.cli.logging", LogLevel.INFO, "before")) {}
        sink.close()
        sink.apply(LogRecordData("test.cli.logging", LogLevel.INFO, "after")) {}
        assertEquals(listOf("before"), written)
    }

    @Test fun `logs reach the console again after restore`() = runTest {
        val result = captureCli {
            withCliLogging(null) { }
            logger.info("back on the console")
            0
        }
        assertContains(result.out, "back on the console")
    }
}
