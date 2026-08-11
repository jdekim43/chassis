package kim.jade.chassis.cli

import kim.jade.kotlinx.logger.LogLevel
import kim.jade.kotlinx.logger.LogRecord
import kim.jade.kotlinx.logger.Logger
import kim.jade.kotlinx.logger.SerializedLog
import kim.jade.kotlinx.logger.pipeline.LogPipe
import kim.jade.kotlinx.logger.pipeline.LogPipeline
import kim.jade.kotlinx.logger.pipeline.StdOutSink
import java.io.FileOutputStream
import java.io.PrintStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class CliLogSink(write: (LogRecord) -> Unit) : LogPipe {
    companion object Key : LogPipe.Key<CliLogSink>

    override val key: LogPipe.Key<out LogPipe> = Key

    private val lock = ReentrantLock()
    private var write: ((LogRecord) -> Unit)? = write

    override fun apply(record: LogRecord, next: (LogRecord) -> Unit) {
        lock.withLock { write?.invoke(record) }
        next(record)
    }

    fun close() = lock.withLock { write = null }
}

internal object CliLogging {
    private class Swap(
        val pipeline: LogPipeline,
        val sink: CliLogSink,
        val originalStdOutSink: StdOutSink?,
        val file: PrintStream?,
    )

    private var installed = false
    private var swap: Swap? = null

    @Synchronized
    fun install(target: String?) {
        check(!installed) { "CLI logging is already installed; runCli runs once at a time per process" }
        val pipeline = Logger.defaultPipeline
        val original = pipeline[StdOutSink].firstOrNull()
        if (target != null || original != null) {
            val file = when (target) {
                null, "stdout", "stderr" -> null
                else -> PrintStream(FileOutputStream(target, true), false, Charsets.UTF_8)
            }
            val sink = CliLogSink(writer(target, file, original?.printStackTrace ?: true))
            if (original == null) {
                pipeline.install(sink)
            } else {
                val index = pipeline.installIndexOf(StdOutSink)
                pipeline.uninstall(StdOutSink)
                pipeline.install(sink, index)
            }
            swap = Swap(pipeline, sink, original, file)
        }
        installed = true
    }

    @Synchronized
    fun restore() {
        if (!installed) return
        installed = false
        val swap = swap ?: return
        this.swap = null
        val pipeline = swap.pipeline
        swap.sink.close()
        val index = pipeline.installIndexOf(CliLogSink)
        pipeline.uninstall(CliLogSink)
        swap.file?.close()
        val original = swap.originalStdOutSink
        if (original == null || pipeline.isInstalled(StdOutSink)) return
        if (index < 0) pipeline.install(original) else pipeline.install(original, index)
    }

    private fun writer(target: String?, file: PrintStream?, printStackTrace: Boolean): (LogRecord) -> Unit {
        if (file != null) return { file.log(it) }
        val console = StdOutSink(printStackTrace, useStdErr = target != "stdout")
        if (target != null) return { console.apply(it) {} }
        return { if (it.isPrintableAt(LogLevel.ERROR)) console.apply(it) {} }
    }
}

private fun PrintStream.log(record: LogRecord) {
    println((record as? SerializedLog<*>)?.serialized as? String ?: record.body)
    record.exception?.printStackTrace(this)
    flush()
}
