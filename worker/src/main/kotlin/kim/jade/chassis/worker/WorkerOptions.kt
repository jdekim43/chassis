package kim.jade.chassis.worker

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class WorkerOptions(
    val graceful: Duration = 10.seconds,
    val context: CoroutineContext = Dispatchers.Default,
)
