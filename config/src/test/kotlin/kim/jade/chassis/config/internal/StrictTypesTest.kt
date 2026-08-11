package kim.jade.chassis.config.internal

import kim.jade.chassis.config.ConfigurationException
import kim.jade.chassis.config.document.decode
import kim.jade.chassis.config.json.JsonConfigurationFormat
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.serializer

class StrictTypesTest {
    @Serializable enum class Mode { A, @SerialName("bee") B }
    @Serializable data class Server(val host: String, val port: Int = 80, val note: String? = null)
    @Serializable @JvmInline value class Id(val value: String)
    @Serializable sealed interface Shape
    @Serializable data class Holder(val shape: Shape)
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable data class Aliased(
        @SerialName("label") @JsonNames("oldLabel", "olderLabel") val text: String,
        @JsonNames("oldCount") val count: Int,
    )
    @Serializable data class Fleet(val servers: List<Server> = emptyList())
    @Serializable data class RequiredNullable(val value: String?)
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable @JsonIgnoreUnknownKeys data class Lenient(val a: String = "", val inner: Server? = null)

    private inline fun <reified T> check(json: String) = checkTypes(Json.parseToJsonElement(json), serializer<T>().descriptor)
    private inline fun <reified T> rejects(json: String): String =
        assertFailsWith<TypeMismatch>(json) { check<T>(json) }.message.orEmpty()

    @Test fun `values of the declared kind are accepted`() {
        check<Int>("1"); check<Long>("-9223372036854775808"); check<Double>("1"); check<Double>("1.5")
        check<Boolean>("true"); check<String>("\"x\""); check<Char>("\"x\""); check<Mode>("\"bee\"")
        check<Id>("\"i\""); check<UInt>("3000000000"); check<List<Int>>("[1,2]"); check<String?>("null")
        check<Server>("""{"host":"a","note":null}"""); check<Map<String, Server>>("""{"a":{"host":"h"}}""")
        check<Map<Int, String>>("""{"1":"a","-2":"b"}"""); check<Map<Mode, String>>("""{"A":"a","bee":"b"}""")
        check<Map<Id, String>>("""{"k":"v"}""")
        check<Map<UInt, String>>("""{"3000000000":"a"}"""); check<Map<ULong, String>>("""{"18446744073709551615":"a"}""")
    }

    @Test fun `values of another kind are rejected without coercion`() {
        rejects<Int>("\"1\""); rejects<Int>("true"); rejects<Double>("\"1.5\""); rejects<Double>("false")
        rejects<Boolean>("\"true\""); rejects<Boolean>("1"); rejects<String>("1"); rejects<String>("true")
        rejects<String>("null"); rejects<Int>("null"); rejects<Char>("1"); rejects<Mode>("1"); rejects<Id>("1")
        rejects<List<Int>>("""{"a":1}"""); rejects<Server>("[]"); rejects<Server>("\"x\"")
        rejects<Map<String, Int>>("[]"); rejects<Int>("{}"); rejects<Boolean>("[]")
    }

    @Test fun `numeric representation and range are left to the deserializer`() {
        check<Int>("1.5"); check<Byte>("128"); check<Int>("2147483648")
        check<UInt>("-1"); check<ULong>("18446744073709551616")
        check<Float>("1e39"); check<Double>("1e309")
    }

    @Test fun `string contents and map keys are left to the deserializer`() {
        check<Char>("\"\""); check<Char>("\"ab\""); check<Mode>("\"unknown\"")
        check<Map<Int, String>>("""{"01":"a","+1":"b","-0":"c"}""")
        check<Map<Byte, String>>("""{"128":"a"}"""); check<Map<Mode, String>>("""{"unknown":"a"}""")
        check<Map<Double, String>>("""{"0.5":"a"}""")
    }

    @Test fun `a missing required field is rejected at its key`() {
        val missing = assertFailsWith<TypeMismatch> { check<Server>("{}") }
        assertEquals(listOf("host"), missing.place)
        assertEquals("Required value is missing", missing.reason)
        check<Server>("""{"host":"a"}""")
        assertEquals(listOf("value"), assertFailsWith<TypeMismatch> { check<RequiredNullable>("{}") }.place)
    }

    @Test fun `an unknown key is rejected at its key but aliases and map keys are not`() {
        val unknown = assertFailsWith<TypeMismatch> { check<Server>("""{"host":"a","prot":1}""") }
        assertEquals(listOf("prot"), unknown.place)
        assertEquals("Unknown key", unknown.reason)
        check<Aliased>("""{"oldLabel":"x","oldCount":1}""")
        check<Map<String, Server>>("""{"anything":{"host":"h"}}""")
    }

    @Test fun `a class that ignores unknown keys accepts them but its strict fields do not`() {
        check<Lenient>("""{"a":"1","extra":2}""")
        val nested = assertFailsWith<TypeMismatch> { check<Lenient>("""{"inner":{"host":"h","prot":1}}""") }
        assertEquals(listOf("inner", "prot"), nested.place)
    }

    @Test fun `a missing required field is reported before an unknown key`() {
        assertEquals(listOf("host"), assertFailsWith<TypeMismatch> { check<Server>("""{"hots":"a"}""") }.place)
    }

    @Test fun `inside a list the place stops at the list and the reason carries the position`() {
        val failure = assertFailsWith<TypeMismatch> { check<Fleet>("""{"servers":[{"host":"a"},{"port":1}]}""") }
        assertEquals(listOf("servers"), failure.place)
        assertContains(failure.reason, "[1].host")
        assertEquals("Expected a number", assertFailsWith<TypeMismatch> { check<Server>("""{"host":"a","port":"x"}""") }.reason)
    }

    @Test fun `delegated invalid values still fail document decoding`() {
        fun document(value: String) = JsonConfigurationFormat.parse("""{"value":$value}""", "config")
        assertFailsWith<ConfigurationException.DecodeFailed> { document("1.5").decode<Int>("value") }
        assertFailsWith<ConfigurationException.DecodeFailed> { document("128").decode<Byte>("value") }
        assertFailsWith<ConfigurationException.DecodeFailed> { document("2147483648").decode<Int>("value") }
        assertFailsWith<ConfigurationException.DecodeFailed> { document("-1").decode<UInt>("value") }
        assertFailsWith<ConfigurationException.DecodeFailed> { document("18446744073709551616").decode<ULong>("value") }
        assertFailsWith<ConfigurationException.DecodeFailed> { document("1e39").decode<Float>("value") }
        assertFailsWith<ConfigurationException.DecodeFailed> { document("\"ab\"").decode<Char>("value") }
        assertFailsWith<ConfigurationException.DecodeFailed> { document("\"unknown\"").decode<Mode>("value") }
        assertFailsWith<ConfigurationException.DecodeFailed> { document("{}").decode<Server>("value") }
        assertFailsWith<ConfigurationException.DecodeFailed> {
            document("""{"host":"a","unknown":1}""").decode<Server>("value")
        }
    }

    @Test fun `nested type mismatches retain the field path and list position`() {
        val inMap = assertFailsWith<TypeMismatch> {
            check<Map<String, List<Server>>>("""{"eu":[{"host":"a","port":"x"}]}""")
        }
        assertEquals(listOf("eu"), inMap.place)
        assertContains(inMap.message.orEmpty(), ".eu[0].port")
        val inList = assertFailsWith<TypeMismatch> {
            check<Fleet>("""{"servers":[{"host":"a"},{"host":1}]}""")
        }
        assertEquals(listOf("servers"), inList.place)
        assertContains(inList.message.orEmpty(), "[1].host")
        val field = assertFailsWith<TypeMismatch> { check<Map<String, Server>>("""{"eu":{"host":"a","port":null}}""") }
        assertEquals(listOf("eu", "port"), field.place)
    }

    @Test fun `alternative field names preserve input kinds and report the input key`() {
        for ((value, key) in listOf(
            """{"oldLabel":1,"oldCount":2}""" to "oldLabel",
            """{"oldLabel":"x","oldCount":"2"}""" to "oldCount",
        )) {
            val failure = assertFailsWith<ConfigurationException.DecodeFailed> {
                JsonConfigurationFormat.parse(value, "config").decode<Aliased>()
            }
            assertEquals(listOf(key), failure.path)
            assertEquals("config", failure.source)
        }
        assertEquals(
            Aliased("current", 2),
            JsonConfigurationFormat.parse("""{"oldLabel":1,"label":"current","oldCount":2}""").decode<Aliased>(),
        )
        assertEquals(
            Aliased("first", 2),
            JsonConfigurationFormat.parse("""{"olderLabel":"first","oldLabel":1,"oldCount":2}""").decode<Aliased>(),
        )
    }

    @Test fun `types without a concrete schema are rejected`() {
        rejects<Holder>("""{"shape":{"type":"x"}}"""); rejects<JsonElement>("1")
    }

    @Test fun `type mismatch messages never contain the value`() {
        val secret = "s3cr3t-value"
        for (message in listOf(rejects<Int>("\"$secret\""), rejects<List<Int>>("[1,\"$secret\"]"))) {
            assertFalse(secret in message, message)
        }
    }
}
