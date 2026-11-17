package kim.jade.chassis.cli.testing

import java.io.ByteArrayOutputStream
import java.io.PrintStream

class CliResult(val code: Int, val out: String, val err: String)

/** System.out과 System.err를 바꿔 끼운다. 프로세스 전역이므로 병렬로 실행하지 않는다. */
suspend fun captureCli(block: suspend () -> Int): CliResult {
    val originalOut = System.out
    val originalErr = System.err
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    System.setOut(PrintStream(out, true))
    System.setErr(PrintStream(err, true))
    try {
        val code = block()
        return CliResult(code, out.toString(), err.toString())
    } finally {
        System.setOut(originalOut)
        System.setErr(originalErr)
    }
}
