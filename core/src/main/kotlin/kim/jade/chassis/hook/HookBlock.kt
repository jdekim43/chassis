package kim.jade.chassis.hook

internal class HookBlock(val sequence: Long, private val block: () -> Unit) {
    fun run() = block()
}
