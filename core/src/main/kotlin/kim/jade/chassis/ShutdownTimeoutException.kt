package kim.jade.chassis

import kim.jade.chassis.hook.HookPhase

class ShutdownTimeoutException(val phase: HookPhase) : RuntimeException("Shutdown exceeded its timeout during $phase")
