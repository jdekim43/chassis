package kim.jade.chassis.cli

import kim.jade.chassis.Application
import kim.jade.kotlinx.logger.Logger
import kim.jade.kotlinx.logger.pipeline.LogPipeline
import kim.jade.kotlinx.logger.pipeline.StdOutSink

internal class Created(private val create: () -> Application) : () -> Application {
    var application: Application? = null
        private set

    override fun invoke(): Application = create().also { application = it }
}

internal inline fun <T> withCliLogging(target: String?, block: () -> T): T {
    CliLogging.install(target)
    try {
        return block()
    } finally {
        CliLogging.restore()
    }
}

internal data class ConsoleSinkState(
    val pipeline: LogPipeline,
    val index: Int,
    val sink: StdOutSink?,
    val cliSinkInstalled: Boolean,
)

internal fun consoleSinkState(): ConsoleSinkState {
    val pipeline = Logger.defaultPipeline
    return ConsoleSinkState(
        pipeline,
        pipeline.installIndexOf(StdOutSink),
        pipeline[StdOutSink].firstOrNull(),
        pipeline.isInstalled(CliLogSink),
    )
}
