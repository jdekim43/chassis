package kim.jade.chassis.config.toml

import kim.jade.chassis.config.ConfigurationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class TomlConversionTest {
    private fun root(text: String) = TomlConfiguration.parse(text, "app.toml").root

    @Test fun `values become the JSON tree the table in the spec describes`() {
        val toml = """
            dec = 42
            hex = 0xFF
            under = 1_000
            neg = -17
            f1 = 1.0
            f2 = 1.5e3
            exp = 1e3
            t = true
            basic = "a"
            odt = 1979-05-27T07:32:00Z
            ld = 1979-05-27
            lt = 07:32:00
            spaced = 1979-05-27 07:32:00
            "a.b" = 1
            inline = { x = 1, y = "z" }
            arr = [1, 2, 3]
            [table]
            k = "v"
            [table.sub]
            k = 2
            [[aot]]
            n = 1
            [[aot]]
            n = 2
        """.trimIndent()
        val expected = """
            {"dec":42,"hex":255,"under":1000,"neg":-17,"f1":1.0,"f2":1500.0,"exp":1000,"t":true,"basic":"a",
             "odt":"1979-05-27T07:32:00Z","ld":"1979-05-27","lt":"07:32:00","spaced":"1979-05-27T07:32:00",
             "a.b":1,"inline":{"x":1,"y":"z"},"arr":[1,2,3],"table":{"k":"v","sub":{"k":2}},"aot":[{"n":1},{"n":2}]}
        """
        assertEquals(Json.parseToJsonElement(expected), root(toml))
    }

    @Test fun `key order follows the file`() {
        assertEquals(listOf("b", "a", "c"), root("b = 1\na = 2\nc = 3").keys.toList())
    }

    @Test fun `non-finite numbers and integers beyond 64 bits fail to parse`() {
        for (text in listOf("x = inf", "x = -inf", "x = nan", "x = 9223372036854775808")) {
            val failure = assertFailsWith<ConfigurationException.ParseFailed>(text) { TomlConfiguration.parse(text, "app.toml") }
            assertEquals("app.toml", failure.source)
        }
    }

    @Test fun `a conversion failure names its place without the value`() {
        for ((text, place, literal) in listOf(
            Triple("[a.b]\nc = [1.0, { d = nan }]", "a.b.c[1].d", "nan"),
            Triple("[[servers]]\nport = 1\n[[servers]]\nport = 9223372036854775808", "servers[1].port", "9223372036854775808"),
            // null은 값이 아니라 종류라서 메시지가 이름으로 쓴다.
            Triple("[db]\nsecret = null", "db.secret", null),
        )) {
            val failure = assertFailsWith<ConfigurationException.ParseFailed>(text) { TomlConfiguration.parse(text, "app.toml") }
            val message = failure.cause?.message.orEmpty()
            assertContains(message, "'$place'")
            if (literal != null) assertFalse(literal in message, message)
        }
    }
}
