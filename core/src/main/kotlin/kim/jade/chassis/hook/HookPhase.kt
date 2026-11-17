package kim.jade.chassis.hook

enum class HookPhase(internal val isLaunchPhase: Boolean) {
    Initialize(true),
    Start(true),
    Started(true),
    RequestStop(false),
    Stop(false),
    Release(false),
}
