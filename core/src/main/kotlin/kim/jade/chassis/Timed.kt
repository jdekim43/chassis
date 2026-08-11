package kim.jade.chassis

import kim.jade.kotlinx.logger.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class Stopwatch {
    private var mark = TimeSource.Monotonic.markNow()

    fun lap(): Duration {
        val elapsed = mark.elapsedNow().inWholeMilliseconds.milliseconds
        mark = TimeSource.Monotonic.markNow()
        return elapsed
    }
}

inline fun <T> Logger.timed(what: String, block: () -> T): T {
    debug("$what starting")
    val stopwatch = Stopwatch()
    return block().also { debug("$what finished in ${stopwatch.lap()}") }
}
