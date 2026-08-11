package kim.jade.chassis.config.toml.r8

import com.android.tools.r8.CompilationFailedException
import com.android.tools.r8.Diagnostic
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.JdkClassFileProvider
import com.android.tools.r8.OutputMode
import com.android.tools.r8.R8
import com.android.tools.r8.R8Command
import com.android.tools.r8.StringConsumer
import com.android.tools.r8.origin.Origin
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.io.TempDir

class ShrinkTest {
    @TempDir lateinit var directory: File

    @Test fun `shrunk with the shipped rules, configuration is read, decoded and validated`() {
        val shrunk = shrink(withShippedRules = true)
        assertTrue(shrunk.succeeded, shrunk.diagnostics)

        val valid = run(shrunk.output, toml("valid", VALID))
        assertEquals(0, valid.exitCode, valid.stderr)
        assertContains(valid.stdout, EXPECTED_OUTPUT)

        val json = run(shrunk.output, File(directory, "valid.json").apply { writeText(VALID_JSON) })
        assertEquals(0, json.exitCode, json.stderr)
        assertContains(json.stdout, EXPECTED_OUTPUT)

        val invalid = run(shrunk.output, toml("invalid", VALID.replace("port = 9090", "port = 0")))
        assertNotEquals(0, invalid.exitCode, invalid.stdout)
        assertContains(invalid.stderr, "smoke.port=OUT_OF_RANGE")
    }

    @Test fun `shrinking succeeds without Clikt and chassis-cli among the inputs`() {
        val shrunk = shrink(withShippedRules = true)
        val names = shrunk.programInputs.map { it.name }
        assertTrue(names.none { it.startsWith("clikt") || it.startsWith("chassis-cli") }, names.toString())
        assertTrue(names.none { it.startsWith("kotlinx-coroutines-test") }, names.toString())
        assertTrue(shrunk.succeeded, shrunk.diagnostics)
    }

    @Test fun `shrinking succeeds when an app keeps the whole configuration package without Clikt`() {
        val shrunk = shrink(withShippedRules = true, appRules = listOf("-keep class kim.jade.chassis.config.** { *; }"))
        assertTrue(shrunk.succeeded, shrunk.diagnostics)

        val result = run(shrunk.output, toml("valid", VALID))
        assertEquals(0, result.exitCode, result.stderr)
        assertContains(result.stdout, EXPECTED_OUTPUT)
    }

    @Test fun `without the shipped rules the shrunk program fails`() {
        val shrunk = shrink(withShippedRules = false)
        if (!shrunk.succeeded) return
        val result = run(shrunk.output, toml("valid", VALID))
        assertNotEquals(0, result.exitCode, result.stdout)
    }

    private class Shrunk(val programInputs: List<File>, val output: File, val succeeded: Boolean, val diagnostics: String)

    private class Run(val exitCode: Int, val stdout: String, val stderr: String)

    private fun shrink(withShippedRules: Boolean, appRules: List<String> = emptyList()): Shrunk {
        val inputs = programInputs(withShippedRules)
        val output = File(directory, "shrunk.jar")
        val diagnostics = StringBuilder()
        val configuration = StringBuilder()
        val command = R8Command.builder(object : DiagnosticsHandler {
            override fun error(diagnostic: Diagnostic) {
                diagnostics.appendLine("error: ${diagnostic.diagnosticMessage}")
            }

            override fun warning(diagnostic: Diagnostic) {
                diagnostics.appendLine("warning: ${diagnostic.diagnosticMessage}")
            }

            override fun info(diagnostic: Diagnostic) = Unit
        })
            .setProguardCompatibility(false)
            .addProgramFiles(inputs.map { it.toPath() })
            .addLibraryResourceProvider(JdkClassFileProvider.fromJdkHome(File(System.getProperty("java.home")).toPath()))
            .addProguardConfiguration(
                listOf("-keep class ${SMOKE_MAIN} { public static void main(java.lang.String[]); }") + appRules,
                Origin.unknown(),
            )
            .setProguardConfigurationConsumer(object : StringConsumer {
                override fun accept(string: String, handler: DiagnosticsHandler) {
                    configuration.append(string)
                }
            })
            .setOutput(output.toPath(), OutputMode.ClassFile)
            .build()
        val succeeded = try {
            R8.run(command)
            true
        } catch (_: CompilationFailedException) {
            false
        }
        if (succeeded) {
            assertEquals(withShippedRules, ":$SHIPPED_RULES" in configuration, "R8 configuration:\n$configuration")
        }
        return Shrunk(inputs, output, succeeded, diagnostics.toString())
    }

    private fun programInputs(withShippedRules: Boolean): List<File> {
        val smokeOutput = File(SmokeConfig::class.java.protectionDomain.codeSource.location.toURI())
        val entries = System.getProperty("chassis.test.classpath").split(File.pathSeparator).map(::File)
            .filter { it.exists() && !it.isTestOutput() && !it.isExcludedLibrary() }
        val inputs = mutableListOf(pack(smokeOutput, File(directory, "smoke.jar")) { it.startsWith(SMOKE_PACKAGE + "Smoke") })
        var shippedRulesSeen = false
        for ((index, entry) in entries.withIndex()) {
            val hasShippedRules = entry.containsEntry(SHIPPED_RULES)
            shippedRulesSeen = shippedRulesSeen || hasShippedRules
            inputs += when {
                hasShippedRules && !withShippedRules ->
                    pack(entry, File(directory, "input-$index.jar")) { it != SHIPPED_RULES }
                entry.isDirectory -> pack(entry, File(directory, "input-$index.jar")) { true }
                else -> entry
            }
        }
        assertTrue(shippedRulesSeen, "$SHIPPED_RULES not found on the test classpath")
        return inputs
    }

    private fun run(program: File, configuration: File): Run {
        val stdout = File(directory, "${configuration.nameWithoutExtension}.out")
        val stderr = File(directory, "${configuration.nameWithoutExtension}.err")
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path,
            "-cp", program.path, SMOKE_MAIN, configuration.path,
        ).redirectOutput(stdout).redirectError(stderr).start()
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) fail("Shrunk program timed out")
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
        }
        return Run(process.exitValue(), stdout.readText(), stderr.readText())
    }

    private fun toml(name: String, text: String): File = File(directory, "$name.toml").apply { writeText(text) }

    private fun File.isTestOutput(): Boolean = isDirectory && name == "test"

    private fun File.isExcludedLibrary(): Boolean = EXCLUDED_LIBRARIES.any { name.startsWith(it) }

    private fun File.containsEntry(path: String): Boolean =
        if (isDirectory) File(this, path).isFile else ZipFile(this).use { it.getEntry(path) != null }

    private fun pack(source: File, target: File, include: (String) -> Boolean): File {
        ZipOutputStream(target.outputStream()).use { out ->
            fun copy(name: String, bytes: ByteArray) {
                if (!include(name)) return
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
            if (source.isDirectory) {
                source.walkTopDown().filter { it.isFile }.forEach { copy(it.relativeTo(source).invariantSeparatorsPath, it.readBytes()) }
            } else {
                ZipFile(source).use { zip ->
                    for (entry in zip.entries()) {
                        if (!entry.isDirectory) copy(entry.name, zip.getInputStream(entry).readBytes())
                    }
                }
            }
        }
        return target
    }

    private companion object {
        const val SHIPPED_RULES = "META-INF/proguard/chassis-config.pro"
        const val VALID = "[smoke]\nname = \"shrunk\"\nport = 9090\ntimeout = \"30s\"\n\n[smoke.primary]\nhost = \"p\"\n\n" +
            "[smoke.replicas.a]\nhost = \"ra\"\nport = 7\n\n[smoke.replicas.b]\nport = 8\n\n[named]\ngreeting = \"named\"\n"
        const val VALID_JSON = """{"smoke":{"name":"shrunk","port":9090,"timeout":"30s","primary":{"host":"p"},""" +
            """"replicas":{"a":{"host":"ra","port":7},"b":{"port":8}}},"named":{"greeting":"named"}}"""
        const val EXPECTED_OUTPUT = "name=shrunk port=9090 primary=p:80 replicas=a=ra:7,b=localhost:8 timeout=30s greeting=named"
        const val SMOKE_PACKAGE = "kim/jade/chassis/config/toml/r8/"
        val SMOKE_MAIN = SmokeConfig::class.java.packageName + ".SmokeKt"
        val EXCLUDED_LIBRARIES = listOf(
            "junit-", "opentest4j-", "apiguardian-", "kotlin-test", "kotlinx-coroutines-test", "r8-", "chassis-cli", "clikt",
        )
    }
}
