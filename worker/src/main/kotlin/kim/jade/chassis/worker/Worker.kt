package kim.jade.chassis.worker

interface Worker {
    val name: String get() = this::class.simpleName ?: "worker"
    suspend fun run()

    /**
     * 종료가 요청되면 불린다. 신호만 보내고 바로 돌아와야 한다. `run()`과 다른 코루틴에서 불리고,
     * `run()`이 시작되기 전이나 끝난 뒤에도 불린다.
     */
    fun onRequestStop() {}
}
