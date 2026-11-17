package kim.jade.chassis.config

import kim.jade.chassis.config.document.ConfigurationDocument
import kim.jade.chassis.config.json.JsonConfigurationFormat
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private const val ESCAPED_NAME = "quote\"name back\\slash new\nline accent é"

class ConfigurationResolverTest {
    @Serializable data class Db(val host: String = "localhost", val port: Int = 5432)
    @Serializable enum class Mode { A, B }
    @Serializable @JvmInline value class Id(val value: String)
    @Serializable @JvmInline value class Wrapped(val db: Db)

    @Configuration("catalog")
    data class Catalog(
        val name: String = "default",
        val limit: Int = 10,
        val tags: List<String> = emptyList(),
        val db: Db = Db(),
        val labels: Map<String, String> = emptyMap(),
        val dbs: Map<String, Db> = emptyMap(),
        val note: String? = null,
        val backup: Db? = null,
        @SerialName("max-items") val maxItems: Int = 0,
        val mode: Mode = Mode.A,
        val id: Id = Id("none"),
    )
    @Configuration("required") data class Required(val url: String)
    @Configuration("nullable") data class RequiredNullable(val value: String?)
    @Serializable data class Inner(val must: String)
    @Configuration("outer") data class Outer(val inner: Inner = Inner("x"))
    @Configuration("box") data class Box<T>(val values: List<T> = emptyList())
    @Configuration("checked")
    data class Checked(val limit: Int = 1) : ValidatableConfiguration {
        override fun validate() = if (limit < 0) listOf(ConfigurationIssue("limit", "NEGATIVE")) else emptyList()
    }
    @Configuration("throwing")
    data class Throwing(val cancel: Boolean = false) : ValidatableConfiguration {
        override fun validate(): List<ConfigurationIssue> =
            throw if (cancel) CancellationException("stop") else IllegalStateException("broken")
    }
    data class Unannotated(val name: String = "x")
    @Configuration("plain") data class Plain(val name: String = "x")
    @Configuration("plain", "sub") data class Sub(val value: Int = 0)
    @Configuration("maps")
    data class Maps(
        val nested: Map<String, Map<String, Int>> = emptyMap(),
        val optional: Map<String, String?> = emptyMap(),
        val inners: Map<String, Inner> = emptyMap(),
    )
    @Configuration("company", "worker") data class Worker(val count: Int = 1)
    @Configuration("keyed") data class Keyed(
        val byNumber: Map<Int, Db> = emptyMap(),
        val byMode: Map<Mode, String> = emptyMap(),
        val byRatio: Map<Double, String> = emptyMap(),
        val byUnsigned: Map<UInt, String> = emptyMap(),
    )
    @Serializable data class Server(val host: String, val port: Int = 80)
    @Configuration("fleet") data class Fleet(val servers: List<Server> = emptyList())
    @Configuration("guarded") data class Guarded(val limit: Int = 1) {
        init { require(limit > 0) { "limit must be positive" } }
    }
    @Configuration("text") data class Text(val value: String = "", val big: Long = 0, val ratio: Double = 0.0)
    @Configuration("strict") data class Strict(@kotlinx.serialization.Required val level: Int = 1)
    @Configuration data class Root(val name: String = "root", val db: Db = Db())
    @Configuration data class RootRequired(val url: String)
    @Configuration("wrapping") data class Wrapping(val wrapped: Wrapped = Wrapped(Db("none", 0)))
    @Configuration("v") @JvmInline value class TopValue(val value: String)
    class NotSerializable
    @Configuration("invalid")
    data class InvalidDefault(val limit: Int = -1) : ValidatableConfiguration {
        override fun validate() = if (limit < 0) listOf(ConfigurationIssue("limit", "NEGATIVE")) else emptyList()
    }

    @Serializable data class Noted(val name: String, val note: String? = "none")
    @Configuration("notes") data class Notes(val items: List<Noted> = emptyList())

    @Serializable(with = CustomSerializer::class) data class Custom(val known: String)
    object CustomSerializer : KSerializer<Custom> {
        override val descriptor = buildClassSerialDescriptor("Custom") { element<String>("known") }
        override fun serialize(encoder: Encoder, value: Custom) = throw UnsupportedOperationException()
        override fun deserialize(decoder: Decoder): Custom =
            Custom((decoder as JsonDecoder).decodeJsonElement().jsonObject.getValue("known").jsonPrimitive.content)
    }
    @Configuration("custom") data class WithCustom(val item: Custom = Custom("default"))

    @Serializable @JvmInline value class Wait(val value: Duration)
    @OptIn(ExperimentalSerializationApi::class)
    @Configuration("lenient") @JsonIgnoreUnknownKeys data class Lenient(val a: String = "")
    @Configuration("timing")
    data class Timing(
        val interval: Duration,
        val grace: Duration? = null,
        val steps: List<Duration> = emptyList(),
        val byName: Map<String, Duration> = emptyMap(),
        val wait: Wait? = null,
    )

    @Configuration("escaped")
    data class Escaped(val labels: Map<String, String> = emptyMap(), @SerialName(ESCAPED_NAME) val named: String = "")

    private fun doc(source: String, json: String) = JsonConfigurationFormat.parse(json, source)
    private fun resolver(vararg documents: ConfigurationDocument) =
        ConfigurationResolver().apply { documents.forEach(::add) }

    private val base = doc(
        "base",
        """{"catalog":{"name":"base","limit":1,"tags":["a","b"],"db":{"host":"a","port":5432},
           "labels":{"x":"1","y":"1"},"dbs":{"main":{"host":"m","port":1}},"max-items":7}}""",
    )
    private val local = doc(
        "local",
        """{"catalog":{"name":"local","tags":["c"],"db":{"port":5433},
           "labels":{"y":"2","z":"2"},"dbs":{"main":{"port":2},"extra":{"host":"e"}},"mode":"B","id":"i-1"}}""",
    )

    @Test fun `without documents every field takes its default`() {
        assertEquals(Catalog(), resolver().resolve<Catalog>())
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> { resolver().resolve<Required>() }
        assertEquals("defaults", failure.source)
        assertEquals(listOf("required", "url"), failure.path)
        assertIs<SerializationException>(failure.cause)
    }

    @Test fun `each key is read from the highest priority document that has it`() {
        val catalog = resolver(base, local).resolve<Catalog>()
        assertEquals("local", catalog.name)
        assertEquals(1, catalog.limit)
        assertEquals(Db("a", 5433), catalog.db)
        assertEquals(7, catalog.maxItems)
        assertEquals(Mode.B, catalog.mode)
        assertEquals(Id("i-1"), catalog.id)
    }

    @Test fun `the order of adding is the priority`() {
        val catalog = resolver(local, base).resolve<Catalog>()
        assertEquals("base", catalog.name)
        assertEquals(Db("a", 5432), catalog.db)
    }

    @Test fun `a list comes whole from the highest priority document`() {
        assertEquals(listOf("c"), resolver(base, local).resolve<Catalog>().tags)
    }

    @Test fun `map keys are united and class values mix by key`() {
        val catalog = resolver(base, local).resolve<Catalog>()
        assertEquals(mapOf("x" to "1", "y" to "2", "z" to "2"), catalog.labels)
        assertEquals(listOf("x", "y", "z"), catalog.labels.keys.toList())
        assertEquals(mapOf("main" to Db("m", 2), "extra" to Db("e", 5432)), catalog.dbs)
    }

    @Test fun `an explicit null does not hide a lower value`() {
        val top = doc("top", """{"catalog":{"name":null,"note":null,"db":null,"backup":null}}""")
        val below = doc("below", """{"catalog":{"note":"kept","backup":{"host":"b"}}}""")
        val catalog = resolver(base, below, top).resolve<Catalog>()
        assertEquals("base", catalog.name)
        assertEquals("kept", catalog.note)
        assertEquals(Db("a", 5432), catalog.db)
        assertEquals(Db("b", 5432), catalog.backup)
    }

    @Test fun `a nullable field without a default is still required`() {
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> {
            resolver(doc("only", """{"nullable":{"value":null}}""")).resolve<RequiredNullable>()
        }
        assertEquals(listOf("nullable", "value"), failure.path)
        assertEquals("Required value is missing", failure.reason)
    }

    @Test fun `an unknown key fails even when its document has lower priority`() {
        val typo = doc("typo", """{"catalog":{"nmae":"x"}}""")
        for (documents in listOf(arrayOf(typo, local), arrayOf(local, typo))) {
            val failure = assertFailsWith<ConfigurationException.DecodeFailed> { resolver(*documents).resolve<Catalog>() }
            assertEquals("typo", failure.source)
            assertEquals(listOf("catalog", "nmae"), failure.path)
        }
    }

    @Test fun `unknown keys are found in nested classes and in class values of a map but not among map keys`() {
        val nested = assertFailsWith<ConfigurationException.DecodeFailed> {
            resolver(doc("n", """{"catalog":{"db":{"prot":1}}}""")).resolve<Catalog>()
        }
        assertEquals(listOf("catalog", "db", "prot"), nested.path)
        val inMap = assertFailsWith<ConfigurationException.DecodeFailed> {
            resolver(doc("m", """{"catalog":{"dbs":{"one":{"bogus":1}}}}""")).resolve<Catalog>()
        }
        assertEquals(listOf("catalog", "dbs", "one", "bogus"), inMap.path)
        assertEquals(mapOf("anything" to "goes"), resolver(doc("k", """{"catalog":{"labels":{"anything":"goes"}}}""")).resolve<Catalog>().labels)
    }

    @Test fun `nested maps are layered per inner key`() {
        val low = doc("low", """{"maps":{"nested":{"a":{"x":1,"y":1},"b":{"x":1}}}}""")
        val high = doc("high", """{"maps":{"nested":{"a":{"y":2,"z":2},"c":{"x":3}}}}""")
        assertEquals(
            mapOf("a" to mapOf("x" to 1, "y" to 2, "z" to 2), "b" to mapOf("x" to 1), "c" to mapOf("x" to 3)),
            resolver(low, high).resolve<Maps>().nested,
        )
    }

    @Test fun `an explicit null in a map keeps the lower value and a key that is only null is absent`() {
        val low = doc("low", """{"maps":{"optional":{"kept":"low"}}}""")
        val high = doc("high", """{"maps":{"optional":{"kept":null,"gone":null}}}""")
        assertEquals(mapOf("kept" to "low"), resolver(low, high).resolve<Maps>().optional)
    }

    @Test fun `a required field missing in a map value fails at that field`() {
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> {
            resolver(doc("a", """{"maps":{"inners":{"one":{}}}}"""), doc("b", "{}")).resolve<Maps>()
        }
        assertEquals("b + a", failure.source)
        assertEquals(listOf("maps", "inners", "one", "must"), failure.path)
    }

    @Test fun `a value that is not a table where a class belongs fails unless a table hides it`() {
        val scalar = doc("scalar", """{"catalog":{"db":"oops"}}""")
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> { resolver(local, scalar).resolve<Catalog>() }
        assertEquals("scalar", failure.source)
        assertEquals(listOf("catalog", "db"), failure.path)
        assertEquals(Db("localhost", 5433), resolver(scalar, local).resolve<Catalog>().db)
    }

    @Test fun `a value that is not a table at a parent of the configuration path fails unless a table hides it`() {
        val scalar = doc("scalar", """{"company":3}""")
        val table = doc("table", """{"company":{"worker":{"count":2}}}""")
        for (documents in listOf(arrayOf(scalar), arrayOf(table, scalar))) {
            val failure = assertFailsWith<ConfigurationException.DecodeFailed> { resolver(*documents).resolve<Worker>() }
            assertEquals("scalar", failure.source)
            assertEquals(listOf("company"), failure.path)
            assertEquals("Expected a table", failure.reason)
        }
        assertEquals(Worker(2), resolver(scalar, table).resolve<Worker>())
    }

    @Test fun `an explicit null at a parent of the configuration path is no value`() {
        val empty = doc("empty", """{"company":null}""")
        assertEquals(Worker(), resolver(empty).resolve<Worker>())
        assertEquals(Worker(2), resolver(doc("low", """{"company":{"worker":{"count":2}}}"""), empty).resolve<Worker>())
    }

    @Test fun `a value of the wrong type fails when it is read and is ignored when it is shadowed`() {
        val wrong = doc("wrong", """{"catalog":{"limit":"many"}}""")
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> { resolver(wrong).resolve<Catalog>() }
        assertEquals("wrong", failure.source)
        assertEquals(listOf("catalog", "limit"), failure.path)
        assertEquals(5, resolver(wrong, doc("fix", """{"catalog":{"limit":5}}""")).resolve<Catalog>().limit)
    }

    @Test fun `a required field no document has fails with every source, the path of the field and the reason`() {
        val top = assertFailsWith<ConfigurationException.DecodeFailed> {
            resolver(doc("a", "{}"), doc("b", "{}")).resolve<Required>()
        }
        assertEquals("b + a", top.source)
        assertEquals(listOf("required", "url"), top.path)
        assertEquals("Required value is missing", top.reason)
        assertEquals(
            "Configuration decoding failed (source=b + a, section=required.url): Required value is missing",
            top.message,
        )
        assertIs<SerializationException>(top.cause)
        val nested = assertFailsWith<ConfigurationException.DecodeFailed> {
            resolver(doc("a", """{"outer":{"inner":{}}}""")).resolve<Outer>()
        }
        assertEquals(listOf("outer", "inner", "must"), nested.path)
        assertIs<SerializationException>(nested.cause)
    }

    @Test fun `a class without a path reads the root and takes its defaults without documents`() {
        assertEquals(Root(), resolver().resolve<Root>())
    }

    @Test fun `a class without a path layers documents at the root`() {
        val low = doc("low", """{"name":"low","db":{"host":"a","port":1}}""")
        val high = doc("high", """{"db":{"port":2}}""")
        assertEquals(Root("low", Db("a", 2)), resolver(low, high).resolve<Root>())
    }

    @Test fun `a class without a path names the missing required field at the root`() {
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> { resolver(doc("a", "{}")).resolve<RootRequired>() }
        assertEquals("a", failure.source)
        assertEquals(listOf("url"), failure.path)
        assertIs<SerializationException>(failure.cause)
    }

    @Test fun `a class owns its whole section, so overlapping sections collide`() {
        val document = doc("o", """{"plain":{"name":"n","sub":{"value":1}},"elsewhere":{"ignored":true}}""")
        assertEquals(Sub(1), resolver(document).resolve<Sub>())
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> { resolver(document).resolve<Plain>() }
        assertEquals(listOf("plain", "sub"), failure.path)
        assertEquals("Unknown key", failure.reason)
    }

    @Test fun `generic configuration classes are read with their type argument`() {
        val document = doc("g", """{"box":{"values":[1,2]}}""")
        assertEquals(Box(listOf(1, 2)), resolver(document).resolve<Box<Int>>())
    }

    @Test fun `load replaces the documents but the value is still validated`() {
        val document = doc("d", """{"checked":3}""")
        val loaded = Checked(9)
        assertSame(loaded, resolver(document).resolve { loaded })
        val failure = assertFailsWith<ConfigurationException.ValidationFailed> { resolver(document).resolve { Checked(-1) } }
        assertEquals(listOf(ConfigurationIssue("checked.limit", "NEGATIVE")), failure.issues)
    }

    @Test fun `a decoded value is validated`() {
        val failure = assertFailsWith<ConfigurationException.ValidationFailed> {
            resolver(doc("d", """{"checked":{"limit":-1}}""")).resolve<Checked>()
        }
        assertEquals(listOf(ConfigurationIssue("checked.limit", "NEGATIVE")), failure.issues)
    }

    @Test fun `a default value is validated`() {
        val failure = assertFailsWith<ConfigurationException.ValidationFailed> { resolver().resolve<InvalidDefault>() }
        assertEquals(listOf(ConfigurationIssue("invalid.limit", "NEGATIVE")), failure.issues)
    }

    @Test fun `a throwing validation keeps its cause and cancellation passes through`() {
        val failure = assertFailsWith<ConfigurationException.ValidationFailed> { resolver().resolve<Throwing>() }
        assertIs<IllegalStateException>(failure.cause)
        assertFailsWith<CancellationException> { resolver().resolve { Throwing(cancel = true) } }
    }

    @Test fun `a value class wrapping a class is layered key by key`() {
        val low = doc("low", """{"wrapping":{"wrapped":{"host":"low","port":1}}}""")
        val high = doc("high", """{"wrapping":{"wrapped":{"port":2}}}""")
        assertEquals(Wrapped(Db("low", 2)), resolver(low, high).resolve<Wrapping>().wrapped)
        val typo = doc("typo", """{"wrapping":{"wrapped":{"bogus":1}}}""")
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> { resolver(typo, high).resolve<Wrapping>() }
        assertEquals("typo", failure.source)
        assertEquals(listOf("wrapping", "wrapped", "bogus"), failure.path)
    }

    @Test fun `a top level value class without a value fails like a missing required value`() {
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> { resolver().resolve<TopValue>() }
        assertEquals("defaults", failure.source)
        assertEquals(listOf("v"), failure.path)
        assertEquals("Configuration decoding failed (source=defaults, section=v): No value is present", failure.message)
        assertIs<SerializationException>(failure.cause)
        assertEquals(TopValue("x"), resolver(doc("a", """{"v":"x"}""")).resolve<TopValue>())
    }

    @Test fun `a type without a serializer fails as a decode failure with every source`() {
        val none = assertFailsWith<ConfigurationException.DecodeFailed> { resolver().resolve<Box<NotSerializable>>() }
        assertEquals("defaults", none.source)
        assertEquals(listOf("box"), none.path)
        assertIs<SerializationException>(none.cause)
        val two = assertFailsWith<ConfigurationException.DecodeFailed> {
            resolver(doc("a", "{}"), doc("b", "{}")).resolve<Box<NotSerializable>>()
        }
        assertEquals("b + a", two.source)
    }

    @Test fun `a document added later is seen by the next resolve`() {
        val resolver = resolver(base)
        assertEquals("base", resolver.resolve<Catalog>().name)
        resolver.add(local)
        assertEquals("local", resolver.resolve<Catalog>().name)
    }

    @Test fun `a class without the annotation is rejected`() {
        assertFailsWith<ConfigurationException.MissingAnnotation> { resolver().resolve<Unannotated>() }
    }

    @Test fun `changing the map a document was built from changes nothing`() {
        val catalog = mutableMapOf<String, JsonElement>("name" to JsonPrimitive("first"))
        val map = mutableMapOf<String, JsonElement>("catalog" to JsonObject(catalog))
        val document = ConfigurationDocument(JsonObject(map), "mutable")
        val resolver = resolver(document)
        val first = resolver.resolve<Catalog>()
        catalog["name"] = JsonPrimitive("second")
        map["catalog"] = JsonPrimitive(3)
        assertEquals(first, resolver.resolve<Catalog>())
        assertEquals(doc("same", """{"catalog":{"name":"first"}}""").root, document.root)
    }

    @Test fun `maps keyed by integers and enums are layered per key`() {
        val low = doc("low", """{"keyed":{"byNumber":{"1":{"host":"a","port":1}},"byMode":{"A":"low"}}}""")
        val high = doc("high", """{"keyed":{"byNumber":{"1":{"port":2},"2":{}},"byMode":{"B":"high"}}}""")
        val keyed = resolver(low, high).resolve<Keyed>()
        assertEquals(mapOf(1 to Db("a", 2), 2 to Db()), keyed.byNumber)
        assertEquals(mapOf(Mode.A to "low", Mode.B to "high"), keyed.byMode)
    }

    @Test fun `map key conversion follows the declared serializer`() {
        val keyed = resolver(doc("a", """{"keyed":{"byNumber":{"01":{"port":2}},"byRatio":{"0.5":"x"}}}""")).resolve<Keyed>()
        assertEquals(mapOf(1 to Db(port = 2)), keyed.byNumber)
        assertEquals(mapOf(0.5 to "x"), keyed.byRatio)
        for (json in listOf(
            """{"keyed":{"byNumber":{"invalid":{}}}}""",
            """{"keyed":{"byMode":{"C":"x"}}}""",
        )) {
            val failure = assertFailsWith<ConfigurationException.DecodeFailed>(json) {
                resolver(doc("ok", "{}"), doc("bad", json)).resolve<Keyed>()
            }
            assertEquals("bad + ok", failure.source)
            assertEquals(listOf("keyed"), failure.path)
        }
    }

    @Test fun `a wrong enum name fails deserialization for the section`() {
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> {
            resolver(doc("base", """{"catalog":{"mode":"A"}}"""), doc("bad", """{"catalog":{"mode":"C"}}""")).resolve<Catalog>()
        }
        assertEquals("bad + base", failure.source)
        assertEquals(listOf("catalog"), failure.path)
    }

    @Test fun `an unknown key or a missing field inside a list fails at the list and the reason has the position`() {
        for ((json, reason) in listOf(
            """{"fleet":{"servers":[{"host":"a"},{"host":"b","prot":1}]}}""" to "Unknown key at '.servers[1].prot'",
            """{"fleet":{"servers":[{"host":"a"},{"port":1}]}}""" to "Required value is missing at '.servers[1].host'",
        )) {
            val failure = assertFailsWith<ConfigurationException.DecodeFailed>(json) {
                resolver(doc("ok", """{"fleet":{}}"""), doc("bad", json)).resolve<Fleet>()
            }
            assertEquals("bad", failure.source)
            assertEquals(listOf("fleet", "servers"), failure.path)
            assertEquals(reason, failure.reason)
        }
    }

    @Test fun `an integer beyond its range fails deserialization for the section`() {
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> {
            resolver(doc("a", "{}"), doc("b", """{"catalog":{"dbs":{"main":{"port":3000000000}}}}""")).resolve<Catalog>()
        }
        assertEquals("b + a", failure.source)
        assertEquals(listOf("catalog"), failure.path)
        assertNull(failure.reason)
        assertFalse(failure.cause!!.message!! in failure.message!!)
    }

    @Test fun `a failure only the class can detect is reported for the whole section`() {
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> {
            resolver(doc("a", """{"guarded":{"limit":0}}""")).resolve<Guarded>()
        }
        assertEquals("a", failure.source)
        assertEquals(listOf("guarded"), failure.path)
        assertIs<IllegalArgumentException>(failure.cause)
    }

    @Test fun `strings with special characters and wide numbers come back unchanged`() {
        val value = "quote \" backslash \\ newline \n tab \t accent é emoji 😀 control \u0001 slash /"
        val json = buildJsonObject {
            putJsonObject("text") { put("value", value); put("big", Long.MAX_VALUE); put("ratio", 0.1) }
        }.toString()
        assertEquals(Text(value, Long.MAX_VALUE, 0.1), resolver(doc("a", json)).resolve<Text>())
    }

    @Test fun `keys and names that need escaping and a lone surrogate come back unchanged`() {
        val keys = listOf("quote\"key", "back\\slash", "new\nline", "accent é")
        val json = buildJsonObject {
            putJsonObject("escaped") {
                putJsonObject("labels") { keys.forEach { put(it, it) } }
                put(ESCAPED_NAME, "\uD83D")
            }
        }.toString()
        assertEquals(Escaped(keys.associateWith { it }, "\uD83D"), resolver(doc("a", json)).resolve<Escaped>())
    }

    @Test fun `an empty table for a nullable class is a value`() {
        assertEquals(Db(), resolver(doc("a", """{"catalog":{"backup":{}}}""")).resolve<Catalog>().backup)
    }

    @Test fun `a field marked required fails without a value even though it has a default`() {
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> { resolver(doc("a", "{}")).resolve<Strict>() }
        assertEquals("a", failure.source)
        assertEquals(listOf("strict", "level"), failure.path)
        assertEquals(Strict(3), resolver(doc("a", """{"strict":{"level":3}}""")).resolve<Strict>())
    }

    @Test fun `a lower document's non-table is hidden by a table and the higher document's unknown key is reported`() {
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> {
            resolver(doc("low", """{"catalog":{"db":"oops"}}"""), doc("high", """{"catalog":{"db":{"nmae":1}}}""")).resolve<Catalog>()
        }
        assertEquals("high", failure.source)
        assertEquals(listOf("catalog", "db", "nmae"), failure.path)
    }

    @Test fun `of unknown keys from both documents the first in the merged section is reported`() {
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> {
            resolver(doc("low", """{"catalog":{"lowTypo":1}}"""), doc("high", """{"catalog":{"highTypo":1}}""")).resolve<Catalog>()
        }
        assertEquals("low", failure.source)
        assertEquals(listOf("catalog", "lowTypo"), failure.path)
    }

    @Test fun `an invalid map key written in both documents fails deserialization`() {
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> {
            resolver(doc("low", """{"keyed":{"byNumber":{"invalid":{}}}}"""), doc("high", """{"keyed":{"byNumber":{"invalid":{"port":1}}}}""")).resolve<Keyed>()
        }
        assertEquals("high + low", failure.source)
        assertEquals(listOf("keyed"), failure.path)
    }

    @Test fun `a non-table where a map belongs is hidden by a higher table and fails above it`() {
        val scalar = doc("scalar", """{"catalog":{"labels":"oops"}}""")
        val table = doc("table", """{"catalog":{"labels":{"a":"x"}}}""")
        assertEquals(mapOf("a" to "x"), resolver(scalar, table).resolve<Catalog>().labels)
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> { resolver(table, scalar).resolve<Catalog>() }
        assertEquals("scalar", failure.source)
        assertEquals(listOf("catalog", "labels"), failure.path)
    }

    @Test fun `an unknown key whose value is null is not written`() {
        val nulled = doc("nulled", """{"catalog":{"nmae":null}}""")
        assertEquals("real", resolver(doc("real", """{"catalog":{"name":"real"}}"""), nulled).resolve<Catalog>().name)
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> {
            resolver(doc("real", """{"catalog":{"name":"real"}}"""), doc("typo", """{"catalog":{"nmae":"x"}}""")).resolve<Catalog>()
        }
        assertEquals("typo", failure.source)
        assertEquals(listOf("catalog", "nmae"), failure.path)
    }

    @Test fun `a malformed numeric literal is rejected by deserialization`() {
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> {
            resolver(doc("a", "{}"), doc("wide", """{"text":{"ratio": １}}""")).resolve<Text>()
        }
        assertEquals("wide + a", failure.source)
        assertEquals(listOf("text"), failure.path)
        assertFalse("１" in failure.message.orEmpty())
    }

    @Test fun `a map key whose value is null is not written`() {
        assertEquals(emptyMap(), resolver(doc("a", """{"keyed":{"byNumber":{"01":null}}}""")).resolve<Keyed>().byNumber)
        val labels = resolver(
            doc("low", """{"catalog":{"labels":{"a":null,"b":"1"}}}"""),
            doc("high", """{"catalog":{"labels":{"a":"2"}}}"""),
        ).resolve<Catalog>().labels
        assertEquals(listOf("b" to "1", "a" to "2"), labels.toList())
    }

    @Test fun `an invalid map key in the lower document fails deserialization`() {
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> {
            resolver(doc("low", """{"keyed":{"byNumber":{"invalid":{}}}}"""), doc("high", """{"keyed":{"byNumber":{"1":{}}}}""")).resolve<Keyed>()
        }
        assertEquals("high + low", failure.source)
        assertEquals(listOf("keyed"), failure.path)
    }

    @Test fun `unsigned integer map keys are read across their whole range`() {
        assertEquals(
            mapOf(3000000000u to "big"),
            resolver(doc("a", """{"keyed":{"byUnsigned":{"3000000000":"big"}}}""")).resolve<Keyed>().byUnsigned,
        )
    }

    @Test fun `a table a scalar and a table at one path leave only the top table`() {
        val low = doc("low", """{"catalog":{"db":{"host":"low","port":1}}}""")
        val mid = doc("mid", """{"catalog":{"db":"oops"}}""")
        val high = doc("high", """{"catalog":{"db":{"port":3}}}""")
        assertEquals(Db("localhost", 3), resolver(low, mid, high).resolve<Catalog>().db)
    }

    @Test fun `an empty table above keeps the keys below`() {
        val low = doc("low", """{"catalog":{"db":{"host":"a"}}}""")
        assertEquals(Db("a", 5432), resolver(low, doc("high", """{"catalog":{"db":{}}}""")).resolve<Catalog>().db)
    }

    @Test fun `a null inside a list element is read as null or fails`() {
        assertEquals(
            listOf(Noted("a", null)),
            resolver(doc("a", """{"notes":{"items":[{"name":"a","note":null}]}}""")).resolve<Notes>().items,
        )
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> {
            resolver(doc("bad", """{"notes":{"items":[{"name":null}]}}""")).resolve<Notes>()
        }
        assertEquals("bad", failure.source)
        assertEquals(listOf("notes", "items"), failure.path)
    }

    @Test fun `documents added from many threads are all there`() {
        val resolver = ConfigurationResolver()
        val start = CountDownLatch(1)
        val threads = List(16) { index ->
            thread { start.await(); resolver.add(doc("d$index", """{"catalog":{"labels":{"k$index":"v"}}}""")) }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(List(16) { "k$it" }.toSet(), resolver.resolve<Catalog>().labels.keys)
    }

    @Test fun `a table where a list belongs names the higher file`() {
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> {
            resolver(doc("low", """{"catalog":{"tags":["a"]}}"""), doc("high", """{"catalog":{"tags":{"x":1}}}""")).resolve<Catalog>()
        }
        assertEquals("high", failure.source)
        assertEquals(listOf("catalog", "tags"), failure.path)
    }

    @Test fun `an unknown key fails at that key with the source of its document`() {
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> {
            resolver(doc("typo", """{"catalog":{"nmae":"x"}}"""), local).resolve<Catalog>()
        }
        assertEquals("typo", failure.source)
        assertEquals(listOf("catalog", "nmae"), failure.path)
        assertEquals("Unknown key", failure.reason)
        assertTrue(failure.message!!.endsWith("section=catalog.nmae): Unknown key"), failure.message)
    }

    @Test fun `an unknown key at the root is named instead of the root`() {
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> {
            resolver(doc("a", """{"nmae":"x"}""")).resolve<Root>()
        }
        assertEquals(listOf("nmae"), failure.path)
        assertContains(failure.message!!, "section=nmae)")
    }

    @Test fun `a failure the deserializer finds has no reason and its message hides the cause`() {
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> {
            resolver(doc("a", """{"catalog":{"mode":"C"}}""")).resolve<Catalog>()
        }
        assertEquals(listOf("catalog"), failure.path)
        assertNull(failure.reason)
        assertTrue(failure.message!!.endsWith(")"), failure.message)
        assertFalse(failure.cause!!.message!! in failure.message!!)
    }

    @Test fun `a hand written serializer accepts only the keys its descriptor declares`() {
        assertEquals(WithCustom(Custom("a")), resolver(doc("a", """{"custom":{"item":{"known":"a"}}}""")).resolve<WithCustom>())
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> {
            resolver(doc("a", """{"custom":{"item":{"known":"a","extra":1}}}""")).resolve<WithCustom>()
        }
        assertEquals(listOf("custom", "item", "extra"), failure.path)
        assertEquals("Unknown key", failure.reason)
    }

    @Test fun `a class marked to ignore unknown keys is read with them`() {
        assertEquals(Lenient("1"), resolver(doc("a", """{"lenient":{"a":"1","extra":2}}""")).resolve<Lenient>())
    }

    @Test fun `durations are read in the readable and the ISO notation`() {
        fun interval(text: String) = resolver(doc("a", """{"timing":{"interval":"$text"}}""")).resolve<Timing>().interval
        assertEquals(30.seconds, interval("30s"))
        assertEquals(500.milliseconds, interval("500ms"))
        assertEquals(90.seconds, interval("1m 30s"))
        assertEquals(30.seconds, interval("PT30S"))
    }

    @Test fun `durations are read where they are nullable listed mapped or wrapped`() {
        val timing = resolver(
            doc("a", """{"timing":{"interval":"1s","grace":"2s","steps":["1s","2m"],"byName":{"a":"3s"},"wait":"4s"}}"""),
        ).resolve<Timing>()
        assertEquals(Timing(1.seconds, 2.seconds, listOf(1.seconds, 2.minutes), mapOf("a" to 3.seconds), Wait(4.seconds)), timing)
    }

    @Test fun `a duration that is a number or cannot be read fails at its key without the value`() {
        val number = assertFailsWith<ConfigurationException.DecodeFailed> {
            resolver(doc("a", """{"timing":{"interval":30}}""")).resolve<Timing>()
        }
        assertEquals(listOf("timing", "interval"), number.path)
        assertEquals("Expected a string", number.reason)
        val unreadable = assertFailsWith<ConfigurationException.DecodeFailed> {
            resolver(doc("a", """{"timing":{"interval":"soon"}}""")).resolve<Timing>()
        }
        assertEquals("a", unreadable.source)
        assertEquals(listOf("timing", "interval"), unreadable.path)
        assertEquals("Expected a duration such as '30s' or 'PT30S'", unreadable.reason)
        assertFalse("soon" in unreadable.message!!)
        assertFalse("soon" in unreadable.cause!!.message!!)
        val inList = assertFailsWith<ConfigurationException.DecodeFailed> {
            resolver(doc("a", """{"timing":{"interval":"1s","steps":["1s","oops"]}}""")).resolve<Timing>()
        }
        assertEquals(listOf("timing", "steps"), inList.path)
        assertContains(inList.reason!!, "[1]")
    }
}
